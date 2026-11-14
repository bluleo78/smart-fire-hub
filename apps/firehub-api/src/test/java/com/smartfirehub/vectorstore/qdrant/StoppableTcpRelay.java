package com.smartfirehub.vectorstore.qdrant;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 시험용 TCP 중계 — 같은 로컬 포트를 껐다 켜서 저장소 장애·복구를 흉내 낸다(WD-724).
 *
 * <p>Qdrant 컨테이너를 멈추면 다시 띄울 때 포트가 바뀌어 같은 클라이언트로 복구를 볼 수 없다. 이 중계를 앞에 두면 {@link #stop()} 동안 접속이
 * 거부되고(gRPC UNAVAILABLE — 일시 장애), {@link #start()} 뒤 같은 주소로 다시 붙는다. {@link #blackhole(boolean)} 을
 * 켜면 접속은 받되 바이트를 넘기지 않아, 저장소가 패킷을 버려 호출이 제한 시간까지 걸리는 장애를 흉내 낸다.
 */
final class StoppableTcpRelay implements AutoCloseable {

  private final String targetHost;
  private final int targetPort;
  private final int port;
  private final List<Socket> sockets = new CopyOnWriteArrayList<>();
  private volatile ServerSocket server;
  private volatile boolean blackhole;

  StoppableTcpRelay(String targetHost, int targetPort) throws IOException {
    this.targetHost = targetHost;
    this.targetPort = targetPort;
    try (ServerSocket probe = new ServerSocket(0, 50, InetAddress.getLoopbackAddress())) {
      this.port = probe.getLocalPort();
    }
  }

  /** 클라이언트가 붙을 로컬 포트(껐다 켜도 같다). */
  int port() {
    return port;
  }

  /** 켜면 새 접속을 받기만 하고 어디로도 넘기지 않는다(응답 없음). */
  void blackhole(boolean on) {
    this.blackhole = on;
  }

  /** 접속을 받기 시작한다. */
  synchronized void start() throws IOException {
    ServerSocket s = new ServerSocket();
    s.setReuseAddress(true);
    s.bind(new java.net.InetSocketAddress(InetAddress.getLoopbackAddress(), port));
    server = s;
    Thread acceptor = new Thread(() -> acceptLoop(s), "relay-accept-" + port);
    acceptor.setDaemon(true);
    acceptor.start();
  }

  /** 듣기를 멈추고 열린 연결을 모두 끊는다 — 이후 접속은 거부된다. */
  synchronized void stop() {
    closeQuietly(server);
    server = null;
    for (Socket s : sockets) {
      closeQuietly(s);
    }
    sockets.clear();
  }

  @Override
  public void close() {
    stop();
  }

  private void acceptLoop(ServerSocket s) {
    while (!s.isClosed()) {
      try {
        Socket client = s.accept();
        if (blackhole) {
          sockets.add(client); // 붙들기만 한다 — stop() 이 닫는다
          continue;
        }
        Socket upstream = new Socket(targetHost, targetPort);
        sockets.add(client);
        sockets.add(upstream);
        pipe(client, upstream);
        pipe(upstream, client);
      } catch (IOException e) {
        return; // stop() 이 소켓을 닫았다
      }
    }
  }

  /** 한 방향 바이트 복사. 한쪽이 끝나면 두 소켓을 닫는다. */
  private void pipe(Socket from, Socket to) {
    Thread t =
        new Thread(
            () -> {
              try (InputStream in = from.getInputStream();
                  OutputStream out = to.getOutputStream()) {
                in.transferTo(out);
              } catch (IOException ignored) {
                // 끊김은 정상 종료로 본다
              } finally {
                closeQuietly(from);
                closeQuietly(to);
              }
            },
            "relay-pipe");
    t.setDaemon(true);
    t.start();
  }

  private static void closeQuietly(AutoCloseable c) {
    if (c == null) return;
    try {
      c.close();
    } catch (Exception ignored) {
      // 정리 중 실패는 무시한다
    }
  }
}
