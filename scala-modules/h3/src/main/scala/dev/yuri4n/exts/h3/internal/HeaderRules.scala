package dev.yuri4n.exts.h3.internal

/** The header and cookie syntax Node and h3 accept, checked when the handler sets a value so the
  * mistake surfaces at its source instead of while h34s writes the response.
  */
private[exts] object HeaderRules:
    private val Token        = "^[!#$%&'*+\\-.^_`|~0-9A-Za-z]+$".r
    private val InvalidValue = "[^\\t\\x20-\\x7e\\x80-\\xff]".r

    def requireHeader(name: String, value: String): Unit =
      require(name != null && Token.matches(name), s"Invalid response header name: '$name'")
      require(value != null && InvalidValue.findFirstIn(value).isEmpty, s"Invalid value for response header '$name'")

    def requireCookieName(name: String): Unit =
      require(name != null && Token.matches(name), s"Invalid cookie name: '$name'")
