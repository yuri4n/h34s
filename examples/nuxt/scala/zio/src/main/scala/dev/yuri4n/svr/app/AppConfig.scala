package dev.yuri4n.svr.app

enum Env:
    case Dev, Prod, Int

final case class AppConfig(
    debug: Boolean,
    env: Env,
)

object AppConfig
