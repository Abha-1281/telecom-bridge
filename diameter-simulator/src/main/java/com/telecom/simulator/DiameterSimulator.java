package com.telecom.simulator;

import com.telecom.simulator.handler.DiameterServerInitializer;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * DiameterSimulator — Entry point for the fake Diameter billing server.
 *
 * Uses Netty to create a high-performance TCP server on port 3868.
 *
 * NETTY ARCHITECTURE:
 *
 * Netty uses a "Boss + Worker" thread model:
 *
 * BossGroup (1 thread):
 *   - Accepts incoming TCP connections
 *   - Hands off each connection to a worker thread
 *   - Like a receptionist who greets clients and directs them
 *
 * WorkerGroup (N threads, default = 2 × CPU cores):
 *   - Each worker handles I/O for multiple connections (multiplexed)
 *   - Uses Java NIO (non-blocking I/O) under the hood
 *   - One worker can handle thousands of connections simultaneously
 *   - Like technicians who do the actual work
 *
 * When to use 1 boss thread:
 *   - In practice, one boss thread can accept connections fast enough
 *     for any realistic server. Multiple boss threads are rarely needed.
 *
 * PORT 3868:
 *   - This is the well-known port for Diameter protocol (IANA assigned)
 *   - Like HTTP is 80, HTTPS is 443, Diameter is 3868
 */
public class DiameterSimulator {

    private static final Logger log = LoggerFactory.getLogger(DiameterSimulator.class);
    private static final int DIAMETER_PORT = 3868;

    public static void main(String[] args) throws Exception {
        log.info("╔══════════════════════════════════════════╗");
        log.info("║    Diameter Simulator Starting...        ║");
        log.info("║    Port: {}                             ║", DIAMETER_PORT);
        log.info("╚══════════════════════════════════════════╝");

        // BossGroup: 1 thread to accept incoming connections
        EventLoopGroup bossGroup = new NioEventLoopGroup(1);

        // WorkerGroup: default threads (2 × CPU cores) to handle I/O
        EventLoopGroup workerGroup = new NioEventLoopGroup();

        try {
            ServerBootstrap bootstrap = new ServerBootstrap()
                // Assign thread groups
                .group(bossGroup, workerGroup)

                // Use NIO (non-blocking I/O) server channel
                .channel(NioServerSocketChannel.class)

                // TCP option: accept backlog = max queued connections before refusing
                .option(ChannelOption.SO_BACKLOG, 128)

                // TCP option: keepalive on accepted connections
                .childOption(ChannelOption.SO_KEEPALIVE, true)

                // Disable Nagle's algorithm:
                // TCP_NODELAY = true means small packets are sent immediately
                // Without this, TCP might buffer small Diameter messages (bad for latency)
                .childOption(ChannelOption.TCP_NODELAY, true)

                // The pipeline factory — creates handlers for each new connection
                .childHandler(new DiameterServerInitializer());

            // Bind and start listening
            ChannelFuture future = bootstrap.bind(DIAMETER_PORT).sync();
            log.info("✅ Diameter Simulator listening on port {}", DIAMETER_PORT);
            log.info("   Waiting for connections...");
            log.info("   Handles: CER/CEA | DWR/DWA | CCR/CCA");
            log.info("   CCR response delay: 5-20ms (simulated processing)");

            // Block until server channel closes (i.e., until shutdown)
            future.channel().closeFuture().sync();

        } finally {
            // Graceful shutdown — wait for in-flight requests to complete
            log.info("Shutting down Diameter Simulator...");
            workerGroup.shutdownGracefully();
            bossGroup.shutdownGracefully();
            log.info("Shutdown complete.");
        }
    }
}
