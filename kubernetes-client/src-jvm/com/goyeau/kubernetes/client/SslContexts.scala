package com.goyeau.kubernetes.client

import cats.effect.Sync

import java.io.{ByteArrayInputStream, File, FileInputStream, InputStreamReader}
import java.security.cert.{CertificateFactory, X509Certificate}
import java.security.{KeyStore, SecureRandom, Security}
import java.util.Base64

import javax.net.ssl.{KeyManager, KeyManagerFactory, SSLContext, TrustManager, TrustManagerFactory}
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter
import org.bouncycastle.openssl.{PEMKeyPair, PEMParser}
import org.bouncycastle.cert.X509CertificateHolder

import scala.jdk.CollectionConverters.*

private[client] object SslContexts {
  private val TrustStoreSystemProperty         = "javax.net.ssl.trustStore"
  private val TrustStorePasswordSystemProperty = "javax.net.ssl.trustStorePassword"
  private val KeyStoreSystemProperty           = "javax.net.ssl.keyStore"
  private val KeyStorePasswordSystemProperty   = "javax.net.ssl.keyStorePassword"

  def fromConfig[F[_]: Sync](config: KubeConfig[F]): F[SSLContext] =
    Sync[F].blocking {
      val sslContext = SSLContext.getInstance("TLS")
      sslContext.init(keyManagers(config), trustManagers(config), new SecureRandom)
      sslContext
    }

  @SuppressWarnings(Array("scalafix:DisableSyntax.asInstanceOf"))
  private def keyManagers[F[_]](config: KubeConfig[F]): Array[KeyManager] = {
    // Client certificate
    val certDataStream = config.clientCertData.map(data => new ByteArrayInputStream(Base64.getDecoder.decode(data)))
    val certFileStream = config.clientCertFile.map(_.toNioPath.toFile).map(new FileInputStream(_))

    // Client key
    val keyDataStream = config.clientKeyData.map(data => new ByteArrayInputStream(Base64.getDecoder.decode(data)))
    val keyFileStream = config.clientKeyFile.map(_.toNioPath.toFile).map(new FileInputStream(_))

    val _ = for {
      keyStream  <- keyDataStream.orElse(keyFileStream)
      certStream <- certDataStream.orElse(certFileStream)
      _ = Security.addProvider(new BouncyCastleProvider())
      pemKeyPair = new PEMParser(new InputStreamReader(keyStream)).readObject() match {
        case kp: PEMKeyPair => kp
        case _: X509CertificateHolder =>
          throw new IllegalArgumentException(
            s"failed to parse the private key, it looks like you might be specifying the client certificate instead of the private key"
          )
        case other =>
          throw new IllegalArgumentException(
            s"failed to parse the private key: ${other.getClass.getSimpleName} is not a PEM key-pair"
          )
      }
      privateKey = new JcaPEMKeyConverter().setProvider("BC").getPrivateKey(pemKeyPair.getPrivateKeyInfo)

      certificateFactory = CertificateFactory.getInstance("X509")
      certificate        = certificateFactory.generateCertificate(certStream).asInstanceOf[X509Certificate]
    } yield defaultKeyStore.setKeyEntry(
      certificate.getSubjectX500Principal.getName,
      privateKey,
      config.clientKeyPass.fold(Array.empty[Char])(_.toCharArray),
      Array(certificate)
    )

    val keyManagerFactory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm)
    keyManagerFactory.init(defaultKeyStore, Array.empty)
    keyManagerFactory.getKeyManagers
  }

  private lazy val defaultKeyStore = {
    val propertyKeyStoreFile =
      Option(System.getProperty(KeyStoreSystemProperty, "")).filter(_.nonEmpty).map(new File(_))

    val keyStore = KeyStore.getInstance(KeyStore.getDefaultType)
    keyStore.load(
      propertyKeyStoreFile.map(new FileInputStream(_)).orNull,
      System.getProperty(KeyStorePasswordSystemProperty, "").toCharArray
    )
    keyStore
  }

  private def trustManagers[F[_]](config: KubeConfig[F]): Array[TrustManager] = {
    val certDataStream = config.caCertData.map(data => new ByteArrayInputStream(Base64.getDecoder.decode(data)))
    val certFileStream = config.caCertFile.map(_.toNioPath.toFile).map(new FileInputStream(_))

    certDataStream.orElse(certFileStream).foreach { certStream =>
      val certificateFactory = CertificateFactory.getInstance("X509")
      val certificates       = certificateFactory.generateCertificates(certStream).asScala
      certificates
        .map(_.asInstanceOf[X509Certificate]) // scalafix:ok
        .zipWithIndex
        .foreach { case (certificate, i) =>
          val alias = s"${certificate.getSubjectX500Principal.getName}-$i"
          defaultTrustStore.setCertificateEntry(alias, certificate)
        }
    }

    val trustManagerFactory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm)
    trustManagerFactory.init(defaultTrustStore)
    trustManagerFactory.getTrustManagers
  }

  private lazy val defaultTrustStore = {
    val securityDirectory = s"${System.getProperty("java.home")}/lib/security"

    val propertyTrustStoreFile =
      Option(System.getProperty(TrustStoreSystemProperty, "")).filter(_.nonEmpty).map(new File(_))
    val jssecacertsFile = Option(new File(s"$securityDirectory/jssecacerts")).filter(f => f.exists && f.isFile)
    val cacertsFile     = new File(s"$securityDirectory/cacerts")

    val keyStore = KeyStore.getInstance(KeyStore.getDefaultType)
    keyStore.load(
      new FileInputStream(propertyTrustStoreFile.orElse(jssecacertsFile).getOrElse(cacertsFile)),
      System.getProperty(TrustStorePasswordSystemProperty, "changeit").toCharArray
    )
    keyStore
  }
}
