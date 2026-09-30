package dev.yuri4n.exts.h3

import typings.h3.anon.H3EventEventHandlerReques
import typings.h3.mod.{EventHandlerRequest, EventHandlerResponse, EventHandler_, H3Event}

type H3Event1        = H3Event[EventHandlerRequest]
type AnyEvHandlerRes = EventHandlerResponse[Any]
type CommonEvHandler = EventHandler_[EventHandlerRequest, AnyEvHandlerRes]

given Conversion[H3Event1, H3EventEventHandlerReques] =
  _.asInstanceOf[H3EventEventHandlerReques]

given Conversion[H3EventEventHandlerReques, H3Event1] =
  _.asInstanceOf[H3Event1]
