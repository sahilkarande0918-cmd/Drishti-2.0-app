package com.example.util

import android.util.Base64
import android.util.Log
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

object SmtpSender {
    private const val TAG = "SmtpSender"

    fun sendEmail(
        host: String,
        port: Int,
        username: String,
        password: String,
        to: String,
        subject: String,
        body: String
    ): Boolean {
        var socket: SSLSocket? = null
        var reader: BufferedReader? = null
        var writer: BufferedWriter? = null
        try {
            Log.d(TAG, "Connecting to SMTP server $host:$port...")
            val factory = SSLSocketFactory.getDefault() as SSLSocketFactory
            socket = factory.createSocket(host, port) as SSLSocket
            socket.startHandshake()

            reader = BufferedReader(InputStreamReader(socket.inputStream))
            writer = BufferedWriter(OutputStreamWriter(socket.outputStream))

            fun readResponse(): String {
                val line = reader.readLine() ?: ""
                Log.d(TAG, "SMTP Server: $line")
                return line
            }

            fun sendCommand(cmd: String) {
                Log.d(TAG, "SMTP Client: $cmd")
                writer.write(cmd + "\r\n")
                writer.flush()
            }

            // 1. Read greeting
            readResponse()

            // 2. EHLO
            sendCommand("EHLO localhost")
            var line = readResponse()
            while (line.startsWith("250-")) {
                line = readResponse()
            }

            // 3. AUTH LOGIN
            sendCommand("AUTH LOGIN")
            readResponse() // 334 VXNlcm5hbWU6 (Username: in base64)

            // 4. Send username (base64)
            val userBase64 = Base64.encodeToString(username.toByteArray(), Base64.NO_WRAP)
            sendCommand(userBase64)
            readResponse() // 334 UGFzc3dvcmQ6 (Password: in base64)

            // 5. Send password (base64)
            val passBase64 = Base64.encodeToString(password.toByteArray(), Base64.NO_WRAP)
            sendCommand(passBase64)
            val authRes = readResponse()
            if (!authRes.startsWith("235")) {
                Log.e(TAG, "SMTP authentication failed: $authRes")
                return false
            }

            // 6. MAIL FROM
            sendCommand("MAIL FROM:<$username>")
            readResponse()

            // 7. RCPT TO
            sendCommand("RCPT TO:<$to>")
            readResponse()

            // 8. DATA
            sendCommand("DATA")
            readResponse() // 354 Start mail input

            // 9. Send headers and body.
            // RFC 5321 requires CRLF between lines; a bare LF makes stricter servers
            // (Gmail among them) reject or mangle the alert. Body lines starting with
            // "." must also be escaped, or the message terminates early and the
            // guardian receives a truncated emergency alert.
            val messageLines = buildList {
                add("From: $username")
                add("To: $to")
                add("Subject: $subject")
                add("Content-Type: text/plain; charset=UTF-8")
                add("")
                addAll(body.replace("\r\n", "\n").split("\n"))
            }
            val messageData = messageLines.joinToString("\r\n") { line ->
                if (line.startsWith(".")) ".$line" else line
            }

            sendCommand(messageData)
            sendCommand(".")
            val sendRes = readResponse()
            
            // 10. QUIT
            sendCommand("QUIT")
            readResponse()

            return sendRes.startsWith("250")
        } catch (e: Exception) {
            Log.e(TAG, "SMTP sending failed with exception", e)
            return false
        } finally {
            try { writer?.close() } catch (e: Exception) {}
            try { reader?.close() } catch (e: Exception) {}
            try { socket?.close() } catch (e: Exception) {}
        }
    }
}
