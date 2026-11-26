package com.example.cpen321application.ui.trade

import java.util.Locale

internal fun Double.fmt() = String.format(Locale.US, "%.2f", this)

internal fun Double.usd() = "$${fmt()}"
