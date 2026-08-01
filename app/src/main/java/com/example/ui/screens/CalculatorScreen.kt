package com.example.ui.screens

import android.widget.Toast
import com.example.util.ToastUtils
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.MainViewModel

@Composable
fun CalculatorScreen(
    viewModel: MainViewModel,
    onUnlocked: () -> Unit
) {
    var displayExpression by remember { mutableStateOf("") }
    var resultText by remember { mutableStateOf("0") }
    var historyText by remember { mutableStateOf("") }
    val context = LocalContext.current

    val onButtonClick: (String) -> Unit = { symbol ->
        when (symbol) {
            "AC" -> {
                displayExpression = ""
                resultText = "0"
                historyText = ""
            }
            "C" -> {
                if (displayExpression.isNotEmpty()) {
                    displayExpression = displayExpression.dropLast(1)
                }
            }
            "±" -> {
                if (displayExpression.startsWith("-")) {
                    displayExpression = displayExpression.removePrefix("-")
                } else if (displayExpression.isNotEmpty()) {
                    displayExpression = "-$displayExpression"
                }
            }
            "=" -> {
                // Check Secret PIN first
                if (viewModel.unlockWithPin(displayExpression)) {
                    ToastUtils.showSingleToast(context, "Access Granted: Admin Mode Unlocked")
                    onUnlocked()
                } else {
                    // Evaluate standard math
                    try {
                        val evalResult = evaluateSimpleExpression(displayExpression)
                        historyText = "$displayExpression ="
                        resultText = evalResult
                        displayExpression = evalResult
                    } catch (_: Exception) {
                        resultText = "Error"
                    }
                }
            }
            else -> {
                if (resultText == "Error") {
                    displayExpression = ""
                    resultText = "0"
                }
                displayExpression += symbol
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF17181C))
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // Header Bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Calculator",
                    color = Color.White.copy(alpha = 0.7f),
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = "v2.4",
                    color = Color.White.copy(alpha = 0.3f),
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace
                )
            }

            // Display Screen Area
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(bottom = 16.dp),
                verticalArrangement = Arrangement.Bottom,
                horizontalAlignment = Alignment.End
            ) {
                if (historyText.isNotEmpty()) {
                    Text(
                        text = historyText,
                        color = Color.White.copy(alpha = 0.5f),
                        fontSize = 20.sp,
                        fontFamily = FontFamily.Monospace,
                        textAlign = TextAlign.End,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                }

                Text(
                    text = if (displayExpression.isEmpty()) resultText else displayExpression,
                    color = Color.White,
                    fontSize = if ((displayExpression.length) > 10) 36.sp else 48.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    textAlign = TextAlign.End,
                    maxLines = 2,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            // Keypad Grid
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                val buttonRows = listOf(
                    listOf("AC", "C", "%", "÷"),
                    listOf("7", "8", "9", "×"),
                    listOf("4", "5", "6", "-"),
                    listOf("1", "2", "3", "+"),
                    listOf("±", "0", ".", "=")
                )

                buttonRows.forEach { row ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        row.forEach { symbol ->
                            CalculatorButton(
                                symbol = symbol,
                                modifier = Modifier.weight(1f),
                                onClick = { onButtonClick(symbol) },
                                onLongClick = {
                                    if (symbol == "=") {
                                        if (viewModel.unlockWithPin(displayExpression)) {
                                            ToastUtils.showSingleToast(context, "Access Granted: Admin Mode Unlocked")
                                            onUnlocked()
                                        } else {
                                            ToastUtils.showSingleToast(context, "Tip: Enter secret passcode and press '='")
                                        }
                                    }
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun CalculatorButton(
    symbol: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    onLongClick: () -> Unit = {}
) {
    val isOperator = symbol in listOf("÷", "×", "-", "+", "=")
    val isAction = symbol in listOf("AC", "C", "%", "±")

    val containerColor = when {
        symbol == "=" -> Color(0xFF2563EB) // Bright Blue
        isOperator -> Color(0xFF2D3748)
        isAction -> Color(0xFF374151)
        else -> Color(0xFF1E293B)
    }

    val contentColor = when {
        symbol == "=" -> Color.White
        isOperator -> Color(0xFF60A5FA)
        isAction -> Color(0xFFF87171)
        else -> Color.White
    }

    Box(
        modifier = modifier
            .height(72.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(containerColor)
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            )
            .testTag("calc_button_$symbol"),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = symbol,
            color = contentColor,
            fontSize = if (symbol.length > 1) 20.sp else 26.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.SansSerif
        )
    }
}

private fun evaluateSimpleExpression(expression: String): String {
    if (expression.isBlank()) return "0"
    try {
        var clean = expression.replace("×", "*").replace("÷", "/")
        // Handle simple addition, subtraction, multiplication, division
        if (clean.contains("+")) {
            val parts = clean.split("+")
            if (parts.size == 2) {
                val a = parts[0].trim().toDoubleOrNull() ?: 0.0
                val b = parts[1].trim().toDoubleOrNull() ?: 0.0
                return formatResult(a + b)
            }
        } else if (clean.contains("-")) {
            val parts = clean.split("-")
            if (parts.size == 2) {
                val a = parts[0].trim().toDoubleOrNull() ?: 0.0
                val b = parts[1].trim().toDoubleOrNull() ?: 0.0
                return formatResult(a - b)
            }
        } else if (clean.contains("*")) {
            val parts = clean.split("*")
            if (parts.size == 2) {
                val a = parts[0].trim().toDoubleOrNull() ?: 0.0
                val b = parts[1].trim().toDoubleOrNull() ?: 0.0
                return formatResult(a * b)
            }
        } else if (clean.contains("/")) {
            val parts = clean.split("/")
            if (parts.size == 2) {
                val a = parts[0].trim().toDoubleOrNull() ?: 0.0
                val b = parts[1].trim().toDoubleOrNull() ?: 0.0
                if (b == 0.0) return "Error"
                return formatResult(a / b)
            }
        } else if (clean.contains("%")) {
            val parts = clean.split("%")
            if (parts.size == 2) {
                val a = parts[0].trim().toDoubleOrNull() ?: 0.0
                val b = parts[1].trim().toDoubleOrNull() ?: 0.0
                return formatResult(a % b)
            }
        }
        val singleVal = clean.toDoubleOrNull()
        return if (singleVal != null) formatResult(singleVal) else clean
    } catch (_: Exception) {
        return "Error"
    }
}

private fun formatResult(value: Double): String {
    return if (value % 1.0 == 0.0) {
        value.toLong().toString()
    } else {
        String.format("%.4f", value).trimEnd('0').trimEnd('.')
    }
}
