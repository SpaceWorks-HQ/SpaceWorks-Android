package org.spaceworks.app.auth

import org.spaceworks.app.platform.MobileText
// Release binaries contain no demo gateway or demo fixtures.
object DemoSupport { fun create(text:MobileText=MobileText.ENGLISH):AuthBundle?=null }
