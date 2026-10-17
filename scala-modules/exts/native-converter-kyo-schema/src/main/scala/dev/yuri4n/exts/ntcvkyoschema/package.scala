package dev.yuri4n.exts.ntcvkyoschema

/** Optional bridge from a Kyo `Schema` to a `NativeConverter`.
  *
  * This module only reserves the artifact and its dependencies (kyo-schema, kyo-schema-json,
  * kyo-schema-yaml). The `NativeSchema` API arrives in a later change; nothing here is public yet.
  */
private[ntcvkyoschema] object ModuleInfo:
    val name: String = "native-converter-kyo-schema-ext"
