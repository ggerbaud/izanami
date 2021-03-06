package izanami.features

import akka.actor.ActorSystem
import akka.event.Logging
import akka.stream.scaladsl.{BroadcastHub, Keep, Sink, Source}
import akka.stream.{Materializer, OverflowStrategy}
import akka.util.Timeout
import izanami.FeatureEvent.{FeatureCreated, FeatureDeleted, FeatureUpdated}
import izanami._
import izanami.commons.{PatternsUtil, SmartCacheStrategyActor}
import izanami.scaladsl.{FeatureClient, Features}
import play.api.libs.json.JsObject

import scala.collection.immutable
import scala.concurrent.Future
import scala.concurrent.duration.DurationDouble
import scala.util.{Failure, Success}

object SmartCacheFeatureClient {

  def apply(clientConfig: ClientConfig,
            underlyingStrategy: FeatureClient,
            fallback: Features,
            config: SmartCacheStrategy)(implicit izanamiDispatcher: IzanamiDispatcher,
                                        actorSystem: ActorSystem,
                                        materializer: Materializer): SmartCacheFeatureClient =
    new SmartCacheFeatureClient(clientConfig, underlyingStrategy, fallback, config)
}

private[features] class SmartCacheFeatureClient(
    clientConfig: ClientConfig,
    underlyingStrategy: FeatureClient,
    fallback: Features,
    config: SmartCacheStrategy
)(implicit val izanamiDispatcher: IzanamiDispatcher, actorSystem: ActorSystem, val materializer: Materializer)
    extends FeatureClient {

  import akka.pattern._
  import izanami.commons.SmartCacheStrategyActor._
  import izanamiDispatcher.ec

  private def handleFailure[T](v: T): PartialFunction[Throwable, T] = {
    case e =>
      logger.error(e, "Failure during call")
      v
  }

  implicit val timeout = Timeout(10.second)

  private val logger = Logging(actorSystem, this.getClass.getSimpleName)

  private val ref = actorSystem.actorOf(
    SmartCacheStrategyActor.props(
      izanamiDispatcher,
      config.patterns,
      fetchDatas,
      config,
      fallback.featuresSeq.map(f => (f.id, f)).toMap,
      onValueUpdated
    )
  )

  private val (queue, internalEventSource) = Source
    .queue[FeatureEvent](100, OverflowStrategy.backpressure)
    .toMat(BroadcastHub.sink(1024))(Keep.both)
    .run()

  def onValueUpdated(updates: Seq[CacheEvent[Feature]]): Unit =
    updates.foreach {
      case ValueCreated(k, v)      => queue.offer(FeatureCreated(None, k, v))
      case ValueUpdated(k, v, old) => queue.offer(FeatureUpdated(None, k, v, old))
      case ValueDeleted(k, _)      => queue.offer(FeatureDeleted(None, k))
    }

  underlyingStrategy
    .featuresSource("*")
    .runWith(Sink.foreach {
      case FeatureCreated(eventId, id, f) =>
        ref ! SetValues(Seq((id, f)), eventId, triggerEvent = false)
      case FeatureUpdated(eventId, id, f, _) =>
        ref ! SetValues(Seq((id, f)), eventId, triggerEvent = false)
      case FeatureDeleted(eventId, id) =>
        ref ! RemoveValues(Seq(id), eventId, triggerEvent = false)
    })

  override def features(pattern: String): Future[Features] = {
    val convertedPattern: String =
      Option(pattern).map(_.replace(".", ":")).getOrElse("*")
    (ref ? GetByPattern(convertedPattern))
      .mapTo[Seq[Feature]]
      .map(features => Features(clientConfig, features, fallback = fallback.featuresSeq))
      .recover(handleFailure(fallback))
  }

  override def features(pattern: String, context: JsObject): Future[Features] =
    context match {
      case JsObject(v) if v.isEmpty =>
        features(pattern)
      case ctx =>
        val features: Future[Features] =
          underlyingStrategy.features(pattern, ctx)
        features.onComplete {
          case Success(f) =>
            // Updating the cache for features without context
            f.featuresSeq
              .filter {
                case _: ScriptFeature       => false
                case _: GlobalScriptFeature => false
                case _                      => true
              }
              .foreach { f =>
                ref ! SetValues(Seq((f.id, f)), None, triggerEvent = true)
              }
          case _ =>
        }
        features
    }

  override def checkFeature(key: String): Future[Boolean] = {
    require(key != null, "key should not be null")
    val convertedKey = key.replace(".", ":")
    (ref ? Get(convertedKey))
      .mapTo[Option[Feature]]
      .map(
        f =>
          f.map(_.isActive(clientConfig))
            .getOrElse(fallback.isActive(convertedKey))
      )
      .recover(handleFailure(fallback.isActive(key)))
  }

  override def checkFeature(key: String, context: JsObject): Future[Boolean] =
    underlyingStrategy.checkFeature(key, context)

  override def featuresSource(pattern: String) =
    underlyingStrategy
      .featuresSource(pattern)
      .merge(
        internalEventSource.filter(f => PatternsUtil.matchPattern(pattern)(f.id))
      )
      .alsoTo(Sink.foreach { e =>
        logger.debug(s"Event $e")
      })

  override def featuresStream(pattern: String) =
    featuresSource(pattern).runWith(Sink.asPublisher(fanout = true))

  private def fetchDatas[T](patterns: Seq[String]): Future[Seq[(String, Feature)]] = {

    val fetched: Future[immutable.Seq[(String, Feature)]] =
      Source(patterns.toList)
        .mapAsync(4) { key =>
          val fFeatures: Future[Features] = underlyingStrategy.features(key)
          fFeatures.onComplete {
            case Failure(e) =>
              logger.error("Error fetching features with pattern {}", key)
            case _ =>
          }
          fFeatures map (_.featuresSeq.map(f => (f.id, f)))
        }
        .mapConcat(s => s.toList)
        .runWith(Sink.seq)

    fetched.onComplete {
      case Failure(e) =>
        logger.error(e, "Error fetching all features for patterns {}", patterns)
      case _ =>
    }

    fetched
  }

}
