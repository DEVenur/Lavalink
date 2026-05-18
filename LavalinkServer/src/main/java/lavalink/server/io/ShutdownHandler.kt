package lavalink.server.io

import org.springframework.web.socket.CloseStatus

class ShutdownHandler(private val socketServer: SocketServer) : Thread("lavalink-shutdown-handler") {
    init {
        isDaemon = false // block JVM shutdown until this handler finishes
    }

    override fun run() {
        // Shut down each session: close voice connections and destroy players before closing WebSocket
        socketServer.contexts.forEach { context ->
            context.runCatching { shutdown() }
            context.runCatching { closeWebSocket(CloseStatus.GOING_AWAY.code) }
        }

        // Shut down shared executors after all sessions are closed
        socketServer.shutdownExecutors()
    }
}
