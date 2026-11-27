package com.example.cpen321application.ui.trade

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val DECIMAL = Regex("^\\d*\\.?\\d*$")

@Composable
internal fun OrderEntryCard(state: TradeState) {
    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Order Details", fontWeight = FontWeight.Bold, fontSize = 18.sp)
            OrderTypeSelector(state.selectedOrderType, state::selectOrderType, !state.isSubmitting)
            OrderInputFields(state)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Estimated Total", fontSize = 15.sp, color = Color.Gray)
                Text(
                    text = if (state.hasPrice) state.totalAmount.usd() else "--",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold
                )
            }
            OrderValidation(state)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TradeButton("BUY", Green, Color(0xFFA5D6A7), state.isBuyEnabled, Modifier.weight(1f)) {
                    state.submit("BUY")
                }
                TradeButton("SELL", Red, Color(0xFFEF9A9A), state.isSellEnabled, Modifier.weight(1f)) {
                    state.submit("SELL")
                }
            }
        }
    }
}

private val ORDER_TYPES = listOf("MARKET" to "Market Order", "LIMIT" to "Limit Order")

@Composable
private fun OrderTypeSelector(selected: String, onSelect: (String) -> Unit, enabled: Boolean) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ORDER_TYPES.forEach { (type, label) ->
            Button(
                onClick = { onSelect(type) },
                enabled = enabled,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (selected == type) MaterialTheme.colorScheme.primary else Color.LightGray
                )
            ) { Text(label) }
        }
    }
}

@Composable
private fun OrderInputFields(state: TradeState) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        DecimalField(state.quantityText, "Quantity (Shares)", "e.g. 10", state::updateQuantity, !state.isSubmitting)
        if (state.selectedOrderType == "LIMIT") {
            DecimalField(
                value = state.limitPriceText,
                label = "Limit Price ($)",
                placeholder = "e.g. ${state.currentMarketPrice?.fmt() ?: "--"}",
                onChange = state::updateLimitPrice,
                enabled = !state.isSubmitting
            )
        }
    }
}

@Composable
private fun DecimalField(value: String, label: String, placeholder: String, onChange: (String) -> Unit, enabled: Boolean) {
    OutlinedTextField(
        value = value,
        enabled = enabled,
        onValueChange = { if (it.matches(DECIMAL)) onChange(it) },
        label = { Text(label) },
        placeholder = { Text(placeholder) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth(),
        singleLine = true
    )
}

@Composable
private fun OrderValidation(state: TradeState) {
    val inputError = state.inputError
    if (inputError != null) {
        Text(inputError, color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
    } else {
        listOfNotNull(state.buyBlocker, state.sellBlocker).forEach {
            Text(it, color = Red, fontSize = 13.sp, fontWeight = FontWeight.Medium)
        }
    }
}

@Composable
private fun TradeButton(
    label: String,
    color: Color,
    disabledColor: Color,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        colors = ButtonDefaults.buttonColors(
            containerColor = color,
            disabledContainerColor = disabledColor,
            contentColor = Color.White,
            disabledContentColor = Color.White.copy(alpha = 0.6f)
        ),
        shape = RoundedCornerShape(10.dp),
        modifier = modifier.height(50.dp)
    ) {
        Text(label, fontWeight = FontWeight.Bold, fontSize = 16.sp)
    }
}
