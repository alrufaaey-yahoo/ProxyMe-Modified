package com.alrufaaey.proxytunnel.proxy;

import java.io.*;
import java.net.*;
import java.util.concurrent.*;
import javax.net.ssl.*;
import java.security.*;
import java.util.*;
import android.util.Log;
import android.util.Base64;

public class ChainProxy {
    private static final String TAG = "ChainProxy";
    // إعدادات السلسلة
    private final String proxy1Host = "157.240.196.32";
    private final int proxy1Port = 8080;
    private final String proxy2Host = "thumbayan.com";
    private final int proxy2Port = 443;
    
    private final String localHost = "127.0.0.1";
    private final int localPort = 2323;
    
    private String proxyUsername = "";
    private String proxyPassword = "";
    
    // إعدادات الأداء
    private final int bufferSize = 131072;
    private final int maxRetries = 999;
    private final int socketTimeout = 45000;
    
    private SSLContext sslContext;
    private ExecutorService executor;
    private ServerSocket serverSocket;
    private volatile boolean running = false;
    
    public ChainProxy() {
        initSSLContext();
        executor = Executors.newCachedThreadPool();
    }

    public void setCredentials(String username, String password) {
        this.proxyUsername = username;
        this.proxyPassword = password;
    }
    
    private void initSSLContext() {
        try {
            sslContext = SSLContext.getInstance("TLS");
            sslContext.init(null, new TrustManager[]{
                new X509TrustManager() {
                    public java.security.cert.X509Certificate[] getAcceptedIssuers() {
                        return null;
                    }
                    public void checkClientTrusted(
                        java.security.cert.X509Certificate[] certs, String authType) {
                    }
                    public void checkServerTrusted(
                        java.security.cert.X509Certificate[] certs, String authType) {
                    }
                }
            }, new SecureRandom());
            
            HttpsURLConnection.setDefaultHostnameVerifier((hostname, session) -> true);
        } catch (Exception e) {
            Log.e(TAG, "SSL Init Error", e);
        }
    }
    
    public synchronized void start() {
        if (running) return;
        running = true;
        new Thread(() -> {
            try {
                serverSocket = new ServerSocket(localPort, 50, InetAddress.getByName(localHost));
                Log.d(TAG, "ChainProxy started on " + localHost + ":" + localPort);
                while (running) {
                    try {
                        Socket clientSocket = serverSocket.accept();
                        executor.execute(() -> handleClient(clientSocket));
                    } catch (IOException e) {
                        if (running) Log.e(TAG, "Accept Error", e);
                    }
                }
            } catch (IOException e) {
                Log.e(TAG, "Server Socket Error", e);
            } finally {
                stop();
            }
        }).start();
    }

    public synchronized void stop() {
        running = false;
        try {
            if (serverSocket != null) {
                serverSocket.close();
            }
        } catch (IOException e) {
            Log.e(TAG, "Stop Error", e);
        }
    }
    
    private byte[] buildProxy1Headers(String targetHost, int targetPort) {
        String proxy2Target = proxy2Host + ":" + proxy2Port;
        return ("CONNECT " + proxy2Target + " HTTP/1.1\r\n" +
                "Host: " + proxy2Target + "\r\n" +
                "Proxy-Connection: keep-alive\r\n" +
                "User-Agent: Mozilla/5.0 (Linux; Android 16; SM-A566B Build/BP2A.250605.031.A3; wv) " +
                "AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/144.0.7559.109 " +
                "Mobile Safari/537.36 [FBAN/InternetOrgApp;FBAV/65.0.0.0.191;]\r\n" +
                "X-Iorg-Bsid: ea4a2c6b-3831-4c50-8044-803bc4c92495\r\n\r\n").getBytes();
    }
    
    private byte[] buildProxy2Headers(String targetHost, int targetPort) {
        String finalTarget = targetHost + ":" + targetPort;
        StringBuilder headers = new StringBuilder();
        headers.append("CONNECT ").append(finalTarget).append(" HTTP/1.1\r\n");
        headers.append("Host: ").append(finalTarget).append("\r\n");
        headers.append("Proxy-Connection: keep-alive\r\n");
        headers.append("User-Agent: Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36\r\n");
        
        if (proxyUsername != null && !proxyUsername.isEmpty()) {
            String auth = proxyUsername + ":" + (proxyPassword != null ? proxyPassword : "");
            String encodedAuth = Base64.encodeToString(auth.getBytes(), Base64.NO_WRAP);
            headers.append("Proxy-Authorization: Basic ").append(encodedAuth).append("\r\n");
        }
        
        headers.append("\r\n");
        return headers.toString().getBytes();
    }
    
    private SSLSocket establishSingleChain(String targetHost, int targetPort) {
        for (int attempt = 0; attempt < 10; attempt++) {
            try {
                Socket socket1 = new Socket();
                socket1.setSoTimeout(15000);
                socket1.setKeepAlive(true);
                socket1.connect(new InetSocketAddress(proxy1Host, proxy1Port), 10000);
                
                OutputStream out1 = socket1.getOutputStream();
                out1.write(buildProxy1Headers(targetHost, targetPort));
                out1.flush();
                
                InputStream in1 = socket1.getInputStream();
                ByteArrayOutputStream response1 = new ByteArrayOutputStream();
                byte[] buffer = new byte[1024];
                int bytesRead;
                String responseStr = "";
                
                while (!responseStr.contains("\r\n\r\n")) {
                    bytesRead = in1.read(buffer);
                    if (bytesRead == -1) break;
                    response1.write(buffer, 0, bytesRead);
                    responseStr = new String(response1.toByteArray());
                }
                
                if (!responseStr.contains("200") && !responseStr.toLowerCase().contains("established")) {
                    socket1.close();
                    Thread.sleep(100);
                    continue;
                }
                
                SSLSocketFactory factory = sslContext.getSocketFactory();
                SSLSocket sslSocket = (SSLSocket) factory.createSocket(
                    socket1, proxy2Host, proxy2Port, true);
                sslSocket.startHandshake();
                
                OutputStream sslOut = sslSocket.getOutputStream();
                sslOut.write(buildProxy2Headers(targetHost, targetPort));
                sslOut.flush();
                
                InputStream sslIn = sslSocket.getInputStream();
                ByteArrayOutputStream response2 = new ByteArrayOutputStream();
                responseStr = "";
                
                while (!responseStr.contains("\r\n\r\n")) {
                    bytesRead = sslIn.read(buffer);
                    if (bytesRead == -1) break;
                    response2.write(buffer, 0, bytesRead);
                    responseStr = new String(response2.toByteArray());
                }
                
                if (!responseStr.contains("200") && !responseStr.toLowerCase().contains("established")) {
                    sslSocket.close();
                    Thread.sleep(100);
                    continue;
                }
                
                sslSocket.setSoTimeout(socketTimeout);
                return sslSocket;
                
            } catch (Exception e) {
                try {
                    Thread.sleep(100);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
                continue;
            }
        }
        return null;
    }
    
    private void handleClient(Socket clientSocket) {
        try {
            clientSocket.setSoTimeout(10000);
            clientSocket.setKeepAlive(true);
            
            InputStream clientIn = clientSocket.getInputStream();
            ByteArrayOutputStream headerData = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int bytesRead;
            String requestText = "";
            
            while (!requestText.contains("\r\n\r\n")) {
                bytesRead = clientIn.read(buffer);
                if (bytesRead == -1) break;
                headerData.write(buffer, 0, bytesRead);
                requestText = new String(headerData.toByteArray());
            }
            
            if (requestText.isEmpty()) {
                clientSocket.close();
                return;
            }
            
            String[] lines = requestText.split("\r\n");
            if (lines.length == 0) return;
            
            boolean isConnect = lines[0].contains("CONNECT");
            String[] targetInfo = parseTarget(lines);
            String targetHost = targetInfo[0];
            int targetPort = Integer.parseInt(targetInfo[1]);
            
            if (targetHost.isEmpty()) {
                clientSocket.close();
                return;
            }
            
            if (isConnect) {
                OutputStream clientOut = clientSocket.getOutputStream();
                clientOut.write("HTTP/1.1 200 Connection Established\r\n\r\n".getBytes());
                clientOut.flush();
                relayTunnel(clientSocket, targetHost, targetPort);
            } else {
                relayParallel(clientSocket, targetHost, targetPort, headerData.toByteArray());
            }
            
        } catch (Exception e) {
            try {
                clientSocket.close();
            } catch (IOException ie) {}
        }
    }
    
    private String[] parseTarget(String[] lines) {
        String targetHost = "";
        int targetPort = 443;
        
        if (lines[0].contains("CONNECT")) {
            String[] parts = lines[0].split(" ");
            if (parts.length >= 2) {
                String target = parts[1];
                if (target.contains(":")) {
                    String[] hostPort = target.split(":");
                    targetHost = hostPort[0];
                    targetPort = Integer.parseInt(hostPort[1]);
                } else {
                    targetHost = target;
                }
            }
        } else {
            for (String line : lines) {
                if (line.toLowerCase().startsWith("host:")) {
                    String hostPart = line.substring(5).trim();
                    if (hostPart.contains(":")) {
                        String[] hostPort = hostPart.split(":");
                        targetHost = hostPort[0];
                        targetPort = Integer.parseInt(hostPort[1]);
                    } else {
                        targetHost = hostPart;
                        targetPort = 80;
                    }
                    break;
                }
            }
        }
        
        return new String[]{targetHost, String.valueOf(targetPort)};
    }
    
    private void relayTunnel(Socket clientSocket, String targetHost, int targetPort) {
        SSLSocket remoteSocket = establishSingleChain(targetHost, targetPort);
        if (remoteSocket == null) {
            try { clientSocket.close(); } catch (IOException e) {}
            return;
        }
        
        executor.execute(() -> pipe(clientSocket, remoteSocket));
        executor.execute(() -> pipe(remoteSocket, clientSocket));
    }
    
    private void relayParallel(Socket clientSocket, String targetHost, int targetPort, byte[] initialData) {
        SSLSocket remoteSocket = establishSingleChain(targetHost, targetPort);
        if (remoteSocket == null) {
            try { clientSocket.close(); } catch (IOException e) {}
            return;
        }
        
        try {
            OutputStream remoteOut = remoteSocket.getOutputStream();
            remoteOut.write(initialData);
            remoteOut.flush();
            
            executor.execute(() -> pipe(clientSocket, remoteSocket));
            executor.execute(() -> pipe(remoteSocket, clientSocket));
        } catch (IOException e) {
            try { clientSocket.close(); remoteSocket.close(); } catch (IOException ie) {}
        }
    }
    
    private void pipe(Socket src, Socket dst) {
        try {
            InputStream in = src.getInputStream();
            OutputStream out = dst.getOutputStream();
            byte[] buffer = new byte[bufferSize];
            int n;
            while ((n = in.read(buffer)) != -1) {
                out.write(buffer, 0, n);
                out.flush();
            }
        } catch (IOException e) {} finally {
            try { src.close(); dst.close(); } catch (IOException e) {}
        }
    }
}
