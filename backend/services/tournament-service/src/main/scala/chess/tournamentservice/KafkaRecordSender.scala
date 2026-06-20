package chess.tournamentservice

import cats.effect.IO

trait KafkaRecordSender:
  def send(topic: String, key: String, value: String): IO[Unit]
  def close(): IO[Unit]
