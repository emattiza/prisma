package cool.graph.client

import akka.actor.{ActorRef, ActorSystem, Props}
import akka.stream.ActorMaterializer
import com.amazonaws.auth.{AWSStaticCredentialsProvider, BasicAWSCredentials}
import com.amazonaws.client.builder.AwsClientBuilder.EndpointConfiguration
import com.amazonaws.services.kinesis.{AmazonKinesis, AmazonKinesisClientBuilder}
import com.amazonaws.services.s3.AmazonS3
import com.typesafe.config.{Config, ConfigFactory}
import com.typesafe.scalalogging.LazyLogging
import cool.graph.aws.AwsInitializers
import cool.graph.aws.cloudwatch.{Cloudwatch, CloudwatchImpl}
import cool.graph.bugsnag.{BugSnagger, BugSnaggerImpl}
import cool.graph.client.authorization.{ClientAuth, ClientAuthImpl}
import cool.graph.client.database.DeferredResolverProvider
import cool.graph.client.finder.{CachedProjectFetcherImpl, ProjectFetcher, ProjectFetcherImpl, RefreshableProjectFetcher}
import cool.graph.client.metrics.ApiMetricsMiddleware
import cool.graph.client.server.{GraphQlRequestHandler, ProjectSchemaBuilder}
import cool.graph.messagebus.Conversions.{ByteMarshaller, ByteUnmarshaller, Unmarshallers}
import cool.graph.messagebus.pubsub.rabbit.{RabbitAkkaPubSub, RabbitAkkaPubSubPublisher, RabbitAkkaPubSubSubscriber}
import cool.graph.messagebus.queue.rabbit.{RabbitQueue, RabbitQueuePublisher}
import cool.graph.messagebus.{Conversions, PubSubPublisher, PubSubSubscriber, QueuePublisher}
import cool.graph.shared.database.GlobalDatabaseManager
import cool.graph.shared.externalServices.{KinesisPublisher, KinesisPublisherImplementation, TestableTime, TestableTimeImplementation}
import cool.graph.shared.functions.lambda.LambdaFunctionEnvironment
import cool.graph.shared.functions.{EndpointResolver, FunctionEnvironment, LiveEndpointResolver}
import cool.graph.shared.{ApiMatrixFactory, DefaultApiMatrix}
import cool.graph.util.ErrorHandlerFactory
import cool.graph.webhook.{Webhook, WebhookCaller, WebhookCallerImplementation}
import scaldi.Module

import scala.concurrent.ExecutionContext
import scala.util.Try

trait ClientInjector {
  implicit val system: ActorSystem
  implicit val materializer: ActorMaterializer
  implicit val bugSnagger: BugSnagger
  implicit val dispatcher: ExecutionContext

  val projectSchemaInvalidationSubscriber: PubSubSubscriber[String]
  val projectSchemaFetcher: RefreshableProjectFetcher
  val functionEnvironment: FunctionEnvironment
  val endpointResolver: EndpointResolver
  val logsPublisher: QueuePublisher[String]
  val webhooksPublisher: QueuePublisher[Webhook]
  val sssEventsPublisher: PubSubPublisher[String]
  val requestPrefix: String
  val cloudwatch: Cloudwatch
  val globalDatabaseManager: GlobalDatabaseManager
  val kinesisAlgoliaSyncQueriesPublisher: KinesisPublisher
  val kinesisApiMetricsPublisher: KinesisPublisher
  val featureMetricActor: ActorRef
  val apiMetricsMiddleware: ApiMetricsMiddleware
  val config: Config
  val testableTime: TestableTime
  val apiMetricsFlushInterval: Int
  val clientAuth: ClientAuth
  val log: String => Unit
  val errorHandlerFactory: ErrorHandlerFactory
  val apiMatrixFactory: ApiMatrixFactory
  val globalApiEndpointManager: GlobalApiEndpointManager
  val deferredResolver: DeferredResolverProvider[_, UserContext]
  val projectSchemaBuilder: ProjectSchemaBuilder
  val graphQlRequestHandler: GraphQlRequestHandler

  implicit val toScaldi: Module
}

trait ClientInjectorImpl extends ClientInjector with LazyLogging {
  implicit val system: ActorSystem
  implicit val materializer: ActorMaterializer
  implicit val dispatcher: ExecutionContext = system.dispatcher
  implicit val bugSnagger: BugSnagger       = BugSnaggerImpl(sys.env("BUGSNAG_API_KEY"))

  implicit val toScaldi: Module

  override lazy val projectSchemaInvalidationSubscriber: RabbitAkkaPubSubSubscriber[String] = {
    val globalRabbitUri                                 = sys.env("GLOBAL_RABBIT_URI")
    implicit val unmarshaller: ByteUnmarshaller[String] = Unmarshallers.ToString

    RabbitAkkaPubSub.subscriber[String](globalRabbitUri, "project-schema-invalidation", durable = true)
  }

  lazy val blockedProjectIds: Vector[String] = Try { sys.env("BLOCKED_PROJECT_IDS").split(",").toVector }.getOrElse(Vector.empty)

  override lazy val projectSchemaFetcher: RefreshableProjectFetcher = CachedProjectFetcherImpl(
    projectFetcher = ProjectFetcherImpl(blockedProjectIds, config),
    projectSchemaInvalidationSubscriber = projectSchemaInvalidationSubscriber
  )

  override lazy val functionEnvironment = LambdaFunctionEnvironment(
    sys.env.getOrElse("LAMBDA_AWS_ACCESS_KEY_ID", "whatever"),
    sys.env.getOrElse("LAMBDA_AWS_SECRET_ACCESS_KEY", "whatever")
  )

  lazy val kinesis: AmazonKinesis = {
    val credentials =
      new BasicAWSCredentials(sys.env("AWS_ACCESS_KEY_ID"), sys.env("AWS_SECRET_ACCESS_KEY"))

    AmazonKinesisClientBuilder.standard
      .withCredentials(new AWSStaticCredentialsProvider(credentials))
      .withEndpointConfiguration(new EndpointConfiguration(sys.env("KINESIS_ENDPOINT"), sys.env("AWS_REGION")))
      .build
  }

  lazy val webhooksPublisher: RabbitQueuePublisher[cool.graph.webhook.Webhook] =
    RabbitQueue.publisher(clusterLocalRabbitUri, "webhooks")(bugSnagger, Webhook.marshaller)
  lazy val sssEventsPublisher: RabbitAkkaPubSubPublisher[String] =
    RabbitAkkaPubSub.publisher[String](clusterLocalRabbitUri, "sss-events", durable = true)(bugSnagger, fromStringMarshaller)

  lazy val clusterLocalRabbitUri                        = sys.env("RABBITMQ_URI")
  lazy val fromStringMarshaller: ByteMarshaller[String] = Conversions.Marshallers.FromString
  lazy val globalDatabaseManager: GlobalDatabaseManager = GlobalDatabaseManager.initializeForSingleRegion(config)
  lazy val endpointResolver                             = LiveEndpointResolver()
  lazy val logsPublisher: RabbitQueuePublisher[String]  = RabbitQueue.publisher[String](clusterLocalRabbitUri, "function-logs")(bugSnagger, fromStringMarshaller)
  lazy val requestPrefix: String                        = sys.env.getOrElse("AWS_REGION", sys.error("AWS Region not found."))
  lazy val cloudwatch                                   = CloudwatchImpl()
  lazy val kinesisAlgoliaSyncQueriesPublisher           = new KinesisPublisherImplementation(streamName = sys.env("KINESIS_STREAM_ALGOLIA_SYNC_QUERY"), kinesis)
  lazy val kinesisApiMetricsPublisher                   = new KinesisPublisherImplementation(streamName = sys.env("KINESIS_STREAM_API_METRICS"), kinesis)
  lazy val featureMetricActor: ActorRef                 = system.actorOf(Props(new FeatureMetricActor(kinesisApiMetricsPublisher, apiMetricsFlushInterval)))
  lazy val apiMetricsMiddleware                         = new ApiMetricsMiddleware(testableTime, featureMetricActor)
  lazy val config: Config                               = ConfigFactory.load()
  lazy val testableTime                                 = new TestableTimeImplementation
  lazy val apiMetricsFlushInterval                      = 10
  lazy val clientAuth                                   = ClientAuthImpl()
  lazy val log: String => Unit                          = (x: String) => logger.info(x)
  lazy val errorHandlerFactory                          = ErrorHandlerFactory(log, cloudwatch, bugSnagger)
  lazy val apiMatrixFactory                             = ApiMatrixFactory(DefaultApiMatrix)
  lazy val s3: AmazonS3                                 = AwsInitializers.createS3()
  lazy val s3Fileupload: AmazonS3                       = AwsInitializers.createS3Fileupload()

  lazy val globalApiEndpointManager = GlobalApiEndpointManager(
    euWest1 = sys.env("API_ENDPOINT_EU_WEST_1"),
    usWest2 = sys.env("API_ENDPOINT_US_WEST_2"),
    apNortheast1 = sys.env("API_ENDPOINT_AP_NORTHEAST_1")
  )
}

trait CommonClientDependencies extends Module with LazyLogging {
  implicit val system: ActorSystem
  implicit val materializer: ActorMaterializer
  implicit val bugSnagger: BugSnaggerImpl = BugSnaggerImpl(sys.env("BUGSNAG_API_KEY"))

  val projectSchemaInvalidationSubscriber: PubSubSubscriber[String]
  val projectSchemaFetcher: ProjectFetcher
  val functionEnvironment: FunctionEnvironment
  val endpointResolver: EndpointResolver
  val logsPublisher: QueuePublisher[String]
  val webhooksPublisher: QueuePublisher[Webhook]
  val sssEventsPublisher: PubSubPublisher[String]
  val requestPrefix: String
  val cloudwatch: Cloudwatch
  val globalDatabaseManager: GlobalDatabaseManager
  val kinesisAlgoliaSyncQueriesPublisher: KinesisPublisher
  val kinesisApiMetricsPublisher: KinesisPublisher
  val featureMetricActor: ActorRef
  val apiMetricsMiddleware: ApiMetricsMiddleware

  lazy val config: Config          = ConfigFactory.load()
  lazy val testableTime            = new TestableTimeImplementation
  lazy val apiMetricsFlushInterval = 10
  lazy val clientAuth              = ClientAuthImpl()
  lazy val log: String => Unit     = (x: String) => logger.info(x)
  lazy val errorHandlerFactory     = ErrorHandlerFactory(log, cloudwatch, bugSnagger)
  lazy val apiMatrixFactory        = ApiMatrixFactory(DefaultApiMatrix)

  lazy val globalApiEndpointManager = GlobalApiEndpointManager(
    euWest1 = sys.env("API_ENDPOINT_EU_WEST_1"),
    usWest2 = sys.env("API_ENDPOINT_US_WEST_2"),
    apNortheast1 = sys.env("API_ENDPOINT_AP_NORTHEAST_1")
  )

  bind[ClientAuth] toNonLazy clientAuth
  bind[TestableTime] toNonLazy testableTime
  bind[GlobalApiEndpointManager] toNonLazy globalApiEndpointManager
  bind[WebhookCaller] toNonLazy new WebhookCallerImplementation()
  bind[BugSnagger] toNonLazy bugSnagger
  bind[ClientAuth] toNonLazy clientAuth
  bind[TestableTime] toNonLazy testableTime
  bind[ApiMatrixFactory] toNonLazy apiMatrixFactory
  bind[WebhookCaller] toNonLazy new WebhookCallerImplementation()
  bind[BugSnagger] toNonLazy bugSnagger

  binding identifiedBy "config" toNonLazy config
  binding identifiedBy "actorSystem" toNonLazy system destroyWith (_.terminate())
  binding identifiedBy "dispatcher" toNonLazy system.dispatcher
  binding identifiedBy "actorMaterializer" toNonLazy materializer
  binding identifiedBy "environment" toNonLazy sys.env.getOrElse("ENVIRONMENT", "local")
  binding identifiedBy "service-name" toNonLazy sys.env.getOrElse("SERVICE_NAME", "local")

  private lazy val blockedProjectIds: Vector[String] = Try { sys.env("BLOCKED_PROJECT_IDS").split(",").toVector }.getOrElse(Vector.empty)
}
