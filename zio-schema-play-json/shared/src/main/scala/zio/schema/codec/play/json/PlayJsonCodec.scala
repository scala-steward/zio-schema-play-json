package zio.schema.codec.play.json

import play.api.libs.json._
import zio.schema.Schema
import zio.schema.codec.play.json.internal.{Formats, JsonSplitter}
import zio.schema.codec.{BinaryCodec, DecodeError}
import zio.stream.ZPipeline
import zio.{Cause, Chunk, ZIO}

import java.nio.CharBuffer
import java.nio.charset.StandardCharsets

object PlayJsonCodec {

  final case class Config(
    ignoreEmptyCollections: Boolean,
    ignoreNullValues: Boolean = true,
    treatStreamsAsArrays: Boolean = false,
  )

  object Config {
    val default: Config = Config(ignoreEmptyCollections = false)
  }

  implicit def playJsonBinaryCodec[A](implicit format: Writes[A] with Reads[A]): BinaryCodec[A] = new BinaryCodec[A] {

    override def encode(value: A): Chunk[Byte] = {
      val json  = Json.stringify(format.writes(value))
      val bytes = StandardCharsets.UTF_8.newEncoder().encode(CharBuffer.wrap(json))
      Chunk.fromByteBuffer(bytes)
    }

    override def streamEncoder: ZPipeline[Any, Nothing, A, Byte] =
      ZPipeline.mapChunks[A, Chunk[Byte]](_.map(encode)).intersperse(Chunk.single('\n'.toByte)).flattenChunks

    override def decode(whole: Chunk[Byte]): Either[DecodeError, A] = {
      try {
        format.reads(Json.parse(whole.toArray)) match {
          case error: JsError      => throw JsResult.Exception(error)
          case JsSuccess(value, _) => Right(value)
        }
      } catch {
        case exception: Throwable => Left(DecodeError.ReadError(Cause.fail(exception), exception.getMessage))
      }
    }

    override def streamDecoder: ZPipeline[Any, DecodeError, Byte, A] =
      ZPipeline.fromChannel {
        ZPipeline.utf8Decode.channel.mapError(cce => DecodeError.ReadError(Cause.fail(cce), cce.getMessage))
      } >>> JsonSplitter.splitOnJsonBoundary >>> ZPipeline.mapZIO { (json: String) =>
        val bytes = StandardCharsets.UTF_8.newEncoder().encode(CharBuffer.wrap(json))
        ZIO.fromEither(decode(Chunk.fromByteBuffer(bytes)))
      }
  }

  def schemaBasedBinaryCodec[A](config: Config)(implicit schema: Schema[A]): BinaryCodec[A] = new BinaryCodec[A] {

    private lazy val w: Writes[A] = schemaWrites(schema)(config)
    private lazy val r: Reads[A]  = schemaReads(schema)

    override def encode(value: A): Chunk[Byte] = {
      val json  = Json.stringify(w.writes(value))
      val bytes = StandardCharsets.UTF_8.newEncoder().encode(CharBuffer.wrap(json))
      Chunk.fromByteBuffer(bytes)
    }

    override def streamEncoder: ZPipeline[Any, Nothing, A, Byte] =
      if (config.treatStreamsAsArrays) {
        val interspersed: ZPipeline[Any, Nothing, A, Byte] = ZPipeline
          .mapChunks[A, Chunk[Byte]](_.map(encode))
          .intersperse(Chunk.single(','.toByte))
          .flattenChunks
        val prepended: ZPipeline[Any, Nothing, A, Byte]    =
          interspersed >>> ZPipeline.prepend(Chunk.single('['.toByte))
        prepended >>> ZPipeline.append(Chunk.single(']'.toByte))
      } else {
        ZPipeline.mapChunks[A, Chunk[Byte]](_.map(encode)).intersperse(Chunk.single('\n'.toByte)).flattenChunks
      }

    override def decode(whole: Chunk[Byte]): Either[DecodeError, A] = {
      try {
        r.reads(Json.parse(new String(whole.toArray, StandardCharsets.UTF_8))) match {
          case error: JsError      => throw JsResult.Exception(error)
          case JsSuccess(value, _) => Right(value)
        }
      } catch {
        case exception: Throwable => Left(DecodeError.ReadError(Cause.fail(exception), exception.getMessage))
      }
    }

    override def streamDecoder: ZPipeline[Any, DecodeError, Byte, A] =
      ZPipeline.utfDecode.mapError(cce => DecodeError.ReadError(Cause.fail(cce), cce.getMessage)) >>>
        (if (config.treatStreamsAsArrays) JsonSplitter.splitJsonArrayElements
         else JsonSplitter.splitOnJsonBoundary) >>>
        ZPipeline.mapZIO { (json: String) =>
          val bytes = StandardCharsets.UTF_8.newEncoder().encode(CharBuffer.wrap(json))
          ZIO.fromEither(decode(Chunk.fromByteBuffer(bytes)))
        }
  }

  def schemaWrites[A](schema: Schema[A])(implicit config: Config = Config.default): Writes[A] =
    Formats.writesSchema(schema, config)

  def schemaReads[A](schema: Schema[A]): Reads[A] = Formats.readsSchema(schema)

  def schemaFormat[A](schema: Schema[A])(implicit config: Config = Config.default): Format[A] =
    Format(schemaReads(schema), schemaWrites(schema)(config))
}
