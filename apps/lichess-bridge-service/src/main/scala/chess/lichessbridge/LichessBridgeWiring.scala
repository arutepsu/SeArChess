package chess.lichessbridge

import cats.effect.{IO, Ref}
import cats.effect.unsafe.implicits.global
import com.comcast.ip4s.{Host, Port}
import org.http4s.ember.server.EmberServerBuilder

/** Composition root: wires all components and starts the HTTP server + worker. */
object LichessBridgeWiring:

  def start(config: LichessBridgeConfig): LichessBridgeRuntime =
    val io: IO[LichessBridgeRuntime] =
      for
        stateRef       <- IO.ref(WorkerState.empty)
        lichessClient   = LichessHttpClient(config.lichessApiBaseUrl)
        streamClient    = JdkLichessStreamClient(config.lichessApiBaseUrl)
        policy          = DefaultChallengePolicy(config, stateRef)
        httpApp         = LichessBridgeRoutes(config, lichessClient, stateRef).routes.orNotFound
        host            = Host
                            .fromString(config.host)
                            .getOrElse(throw RuntimeException(s"Invalid LICHESS_BRIDGE_HTTP_HOST: ${config.host}"))
        port            = Port
                            .fromInt(config.port)
                            .getOrElse(throw RuntimeException(s"Invalid LICHESS_BRIDGE_HTTP_PORT: ${config.port}"))
        (_, shutdownHttp)   <- EmberServerBuilder
                                 .default[IO]
                                 .withHost(host)
                                 .withPort(port)
                                 .withHttpApp(httpApp)
                                 .build
                                 .allocated
        // The resource value is a join-handle IO[Unit]; discard it in production
        // (the fiber runs until shutdown cancels the resource).
        (_, releaseWorker)  <- LichessBridgeWorker
                                 .resource(config, lichessClient, streamClient, policy, stateRef)
                                 .allocated
      yield LichessBridgeRuntime(shutdownHttp, releaseWorker)

    io.unsafeRunSync()
