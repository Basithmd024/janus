package com.janus.app.core

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.BufferedInputStream
import java.io.File
import java.math.BigInteger
import java.net.ServerSocket
import java.net.Socket
import java.security.KeyPair
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLServerSocketFactory

data class FileMetadata(
    val name: String,
    val size: Long,
    val hash: String
)

data class UploadSession(
    val sessionId: String,
    val files: List<FileMetadata>,
    val createdAt: Long
)

class HttpServerManager(
    private val context: Context,
    private val identity: Identity,
    private val onProgress: (sessionId: String, fileHash: String, bytesReceived: Long, totalBytes: Long, name: String) -> Unit,
    private val onComplete: (sessionId: String, fileHash: String, fileName: String, uri: Uri?) -> Unit
) {
    private var serverSocket: ServerSocket? = null
    private var isRunning = false
    private val activeSessions = ConcurrentHashMap<String, UploadSession>()
    private val gson = Gson()

    fun start(port: Int = 53318) {
        if (isRunning) return
        isRunning = true

        Thread {
            try {
                val sslContext = createSSLContext(identity.keyPair ?: java.security.KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair())
                val factory: SSLServerSocketFactory = sslContext.serverSocketFactory
                val sslServer = factory.createServerSocket(port) as SSLServerSocket
                sslServer.needClientAuth = false
                serverSocket = sslServer

                Log.d("JanusHttpServer", "HTTPS Server started on port $port")

                while (isRunning) {
                    try {
                        val clientSocket = sslServer.accept()
                        Thread {
                            handleClient(clientSocket)
                        }.start()
                    } catch (e: Exception) {
                        if (isRunning) {
                            Log.e("JanusHttpServer", "Error accepting client", e)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("JanusHttpServer", "Failed to start HTTPS Server", e)
            }
        }.apply {
            name = "JanusHttpServerThread"
            start()
        }
    }

    fun stop() {
        isRunning = false
        try {
            serverSocket?.close()
        } catch (e: Exception) {
            Log.e("JanusHttpServer", "Error closing server socket", e)
        }
        serverSocket = null
    }

    private fun createSSLContext(keyPair: KeyPair): SSLContext {
        val cert = generateSelfSignedCertificate(keyPair)
        val keyStore = KeyStore.getInstance(KeyStore.getDefaultType())
        keyStore.load(null, null)
        keyStore.setKeyEntry("janus", keyPair.private, "janus_pass".toCharArray(), arrayOf(cert))

        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        kmf.init(keyStore, "janus_pass".toCharArray())

        val sslContext = SSLContext.getInstance("TLS")
        sslContext.init(kmf.keyManagers, null, SecureRandom())
        return sslContext
    }

    private fun generateSelfSignedCertificate(keyPair: KeyPair): X509Certificate {
        val now = System.currentTimeMillis()
        val startDate = Date(now - 24 * 60 * 60 * 1000)
        val endDate = Date(now + 10 * 365 * 24 * 60 * 60 * 1000L)
        val serial = BigInteger.valueOf(now)
        val name = X500Name("CN=Janus Node, O=Janus P2P, C=US")

        val certBuilder = JcaX509v3CertificateBuilder(
            name,
            serial,
            startDate,
            endDate,
            name,
            keyPair.public
        )

        val signer = JcaContentSignerBuilder("SHA256WithRSA").build(keyPair.private)
        return JcaX509CertificateConverter().getCertificate(certBuilder.build(signer))
    }

    private fun handleClient(socket: Socket) {
        try {
            // MOB-06 FIX: Wrap in BufferedInputStream for high-throughput I/O without single-byte overhead
            val rawInput = socket.getInputStream()
            val inputStream = BufferedInputStream(rawInput, 8192)
            val outputStream = socket.getOutputStream()

            val headerLines = mutableListOf<String>()
            var line: String
            while (true) {
                line = readLine(inputStream)
                if (line.isEmpty()) break
                headerLines.add(line)
            }

            if (headerLines.isEmpty()) return

            val requestLine = headerLines[0]
            val parts = requestLine.split(" ")
            if (parts.size < 2) return

            val method = parts[0]
            val path = parts[1]

            var contentLength = 0L
            var isChunked = false
            for (h in headerLines) {
                if (h.startsWith("Content-Length:", ignoreCase = true)) {
                    contentLength = h.substringAfter(":").trim().toLongOrNull() ?: 0L
                }
                if (h.startsWith("Transfer-Encoding:", ignoreCase = true) && h.contains("chunked", ignoreCase = true)) {
                    isChunked = true
                }
            }

            if (method == "POST" && path == "/api/v1/prepare-upload") {
                val body = ByteArray(contentLength.toInt())
                var totalRead = 0
                while (totalRead < contentLength) {
                    val r = inputStream.read(body, totalRead, contentLength.toInt() - totalRead)
                    if (r == -1) break
                    totalRead += r
                }
                val bodyString = String(body)
                val json = gson.fromJson(bodyString, JsonObject::class.java)
                val filesJson = json.getAsJsonArray("files")
                val filesList = mutableListOf<FileMetadata>()

                for (f in filesJson) {
                    val obj = f.asJsonObject
                    filesList.add(
                        FileMetadata(
                            name = obj.get("name").asString,
                            size = obj.get("size").asLong,
                            hash = obj.get("hash").asString
                        )
                    )
                }

                val sessionId = UUID.randomUUID().toString()
                activeSessions[sessionId] = UploadSession(sessionId, filesList, System.currentTimeMillis())

                val respObj = JsonObject().apply {
                    addProperty("session_id", sessionId)
                    addProperty("status", "ready")
                    val acceptedArr = com.google.gson.JsonArray()
                    filesList.forEach { acceptedArr.add(it.name) }
                    add("accepted_files", acceptedArr)
                }
                val respText = gson.toJson(respObj)
                val respBytes = respText.toByteArray()

                val responseHeader = "HTTP/1.1 200 OK\r\n" +
                        "Content-Type: application/json\r\n" +
                        "Content-Length: ${respBytes.size}\r\n" +
                        "Access-Control-Allow-Origin: *\r\n" +
                        "Connection: close\r\n\r\n"

                outputStream.write(responseHeader.toByteArray())
                outputStream.write(respBytes)
                outputStream.flush()
            } else if (method == "POST" && path.startsWith("/api/v1/upload/")) {
                val uploadParts = path.split("/")
                if (uploadParts.size < 6) {
                    sendErrorResponse(outputStream, 400, "Bad Request")
                    return
                }
                val sessionId = uploadParts[4]
                val fileHash = uploadParts[5]

                val session = activeSessions[sessionId]
                if (session == null) {
                    sendErrorResponse(outputStream, 404, "Session Not Found")
                    return
                }

                val fileMeta = session.files.find { it.hash == fileHash }
                if (fileMeta == null) {
                    sendErrorResponse(outputStream, 404, "File Not Found")
                    return
                }

                Log.d("JanusHttpServer", "Receiving file upload: ${fileMeta.name} (${fileMeta.size} bytes)")

                // Read the file body with buffered streaming and progress reporting
                val tempFile = File(context.cacheDir, "janus_${UUID.randomUUID()}")
                tempFile.outputStream().buffered(8192).use { fos ->
                    if (isChunked) {
                        var totalBytesReceived = 0L
                        while (true) {
                            val sizeLine = readChunkLine(inputStream)
                            val chunkSize = sizeLine.trim().split(";")[0].trim().toIntOrNull(16) ?: 0
                            if (chunkSize == 0) {
                                readChunkLine(inputStream) // Consume trailing CRLF
                                break
                            }
                            var bytesRead = 0
                            val buffer = ByteArray(8192)
                            while (bytesRead < chunkSize) {
                                val toRead = minOf(buffer.size, chunkSize - bytesRead)
                                val r = inputStream.read(buffer, 0, toRead)
                                if (r == -1) throw java.io.IOException("Unexpected EOF in chunk data")
                                fos.write(buffer, 0, r)
                                bytesRead += r
                                totalBytesReceived += r
                                onProgress(sessionId, fileHash, totalBytesReceived, fileMeta.size, fileMeta.name)
                            }
                            readChunkLine(inputStream) // Consume CRLF after chunk data
                        }
                    } else {
                        val buffer = ByteArray(8192)
                        var totalBytesReceived = 0L
                        while (totalBytesReceived < contentLength) {
                            val toRead = minOf(buffer.size.toLong(), contentLength - totalBytesReceived).toInt()
                            val r = inputStream.read(buffer, 0, toRead)
                            if (r == -1) break
                            fos.write(buffer, 0, r)
                            totalBytesReceived += r
                            onProgress(sessionId, fileHash, totalBytesReceived, fileMeta.size, fileMeta.name)
                        }
                    }
                }

                // Verify SHA-256 hash
                val md = MessageDigest.getInstance("SHA-256")
                val computedHash = tempFile.inputStream().buffered(8192).use { input ->
                    val buffer = ByteArray(8192)
                    var read: Int
                    while (input.read(buffer).also { read = it } != -1) {
                        md.update(buffer, 0, read)
                    }
                    md.digest().joinToString("") { "%02x".format(it) }
                }

                if (computedHash != fileHash) {
                    Log.e("JanusHttpServer", "Hash mismatch. Expected $fileHash, got $computedHash")
                    tempFile.delete()
                    sendErrorResponse(outputStream, 400, "Hash mismatch")
                    return
                }

                // Save to Downloads
                val uri = saveFileToDownloads(fileMeta.name, tempFile)
                tempFile.delete()

                onComplete(sessionId, fileHash, fileMeta.name, uri)

                val respText = "Upload completed successfully"
                val respBytes = respText.toByteArray()
                val responseHeader = "HTTP/1.1 200 OK\r\n" +
                        "Content-Type: text/plain\r\n" +
                        "Content-Length: ${respBytes.size}\r\n" +
                        "Access-Control-Allow-Origin: *\r\n" +
                        "Connection: close\r\n\r\n"

                outputStream.write(responseHeader.toByteArray())
                outputStream.write(respBytes)
                outputStream.flush()
            } else {
                sendErrorResponse(outputStream, 404, "Not Found")
            }
        } catch (e: Exception) {
            Log.e("JanusHttpServer", "Error handling client connection", e)
        } finally {
            try {
                socket.close()
            } catch (e: Exception) {}
        }
    }

    private fun sendErrorResponse(outputStream: java.io.OutputStream, code: Int, message: String) {
        val respText = "$code $message"
        val respBytes = respText.toByteArray()
        val responseHeader = "HTTP/1.1 $code $message\r\n" +
                "Content-Type: text/plain\r\n" +
                "Content-Length: ${respBytes.size}\r\n" +
                "Access-Control-Allow-Origin: *\r\n" +
                "Connection: close\r\n\r\n"
        outputStream.write(responseHeader.toByteArray())
        outputStream.write(respBytes)
        outputStream.flush()
    }

    private fun saveFileToDownloads(filename: String, file: File): Uri? {
        val resolver = context.contentResolver
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Janus")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
        }

        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Downloads.EXTERNAL_CONTENT_URI
        } else {
            null
        }

        if (collection != null) {
            val uri = resolver.insert(collection, contentValues)
            if (uri != null) {
                resolver.openOutputStream(uri)?.use { outputStream ->
                    file.inputStream().copyTo(outputStream)
                }
                contentValues.clear()
                contentValues.put(MediaStore.MediaColumns.IS_PENDING, 0)
                resolver.update(uri, contentValues, null, null)
                return uri
            }
        } else {
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "Janus")
            if (!dir.exists()) dir.mkdirs()
            val destFile = File(dir, filename)
            file.inputStream().use { input ->
                destFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            return Uri.fromFile(destFile)
        }
        return null
    }

    private fun readLine(inputStream: java.io.InputStream): String {
        val sb = java.lang.StringBuilder()
        while (true) {
            val c = inputStream.read()
            if (c == -1) break
            sb.append(c.toChar())
            if (sb.endsWith("\r\n")) {
                return sb.substring(0, sb.length - 2)
            }
        }
        return sb.toString()
    }

    private fun readChunkLine(inputStream: java.io.InputStream): String {
        val sb = java.lang.StringBuilder()
        while (true) {
            val c = inputStream.read()
            if (c == -1) break
            sb.append(c.toChar())
            if (sb.endsWith("\r\n")) {
                return sb.substring(0, sb.length - 2)
            }
        }
        return sb.toString()
    }
}
