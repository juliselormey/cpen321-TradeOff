package com.example.cpen321application.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.cpen321application.R
import java.util.Locale

private val suppliedStockLogos = mapOf(
    "ABBV" to R.drawable.ic_stock_abbv,
    "ABNB" to R.drawable.ic_stock_abnb,
    "ACN" to R.drawable.ic_stock_acn,
    "ADBE" to R.drawable.ic_stock_adbe,
    "AMAT" to R.drawable.ic_stock_amat,
    "AMD" to R.drawable.ic_stock_amd,
    "AMGN" to R.drawable.ic_stock_amgn,
    "ANET" to R.drawable.ic_stock_anet,
    "APP" to R.drawable.ic_stock_app,
    "AVGO" to R.drawable.ic_stock_avgo,
    "AXP" to R.drawable.ic_stock_axp,
    "BA" to R.drawable.ic_stock_ba,
    "BAC" to R.drawable.ic_stock_bac,
    "BKNG" to R.drawable.ic_stock_bkng,
    "BLK" to R.drawable.ic_stock_blk,
    "C" to R.drawable.ic_stock_c,
    "CAT" to R.drawable.ic_stock_cat,
    "CL" to R.drawable.ic_stock_cl,
    "CMCSA" to R.drawable.ic_stock_cmcsa,
    "CMG" to R.drawable.ic_stock_cmg,
    "COIN" to R.drawable.ic_stock_coin,
    "COP" to R.drawable.ic_stock_cop,
    "COST" to R.drawable.ic_stock_cost,
    "CRM" to R.drawable.ic_stock_crm,
    "CRWD" to R.drawable.ic_stock_crwd,
    "CSCO" to R.drawable.ic_stock_csco,
    "CVX" to R.drawable.ic_stock_cvx,
    "DE" to R.drawable.ic_stock_de,
    "DELL" to R.drawable.ic_stock_dell,
    "DIS" to R.drawable.ic_stock_dis,
    "F" to R.drawable.ic_stock_f,
    "GE" to R.drawable.ic_stock_ge,
    "GILD" to R.drawable.ic_stock_gild,
    "GM" to R.drawable.ic_stock_gm,
    "GS" to R.drawable.ic_stock_gs,
    "HD" to R.drawable.ic_stock_hd,
    "HOOD" to R.drawable.ic_stock_hood,
    "HPQ" to R.drawable.ic_stock_hpq,
    "IBM" to R.drawable.ic_stock_ibm,
    "INTC" to R.drawable.ic_stock_intc,
    "ISRG" to R.drawable.ic_stock_isrg,
    "JNJ" to R.drawable.ic_stock_jnj,
    "JPM" to R.drawable.ic_stock_jpm,
    "KLAC" to R.drawable.ic_stock_klac,
    "KO" to R.drawable.ic_stock_ko,
    "LIN" to R.drawable.ic_stock_lin,
    "LLY" to R.drawable.ic_stock_lly,
    "LMT" to R.drawable.ic_stock_lmt,
    "LOW" to R.drawable.ic_stock_low,
    "LRCX" to R.drawable.ic_stock_lrcx,
    "MA" to R.drawable.ic_stock_ma,
    "MCD" to R.drawable.ic_stock_mcd,
    "MDLZ" to R.drawable.ic_stock_mdlz,
    "META" to R.drawable.ic_stock_meta,
    "MRK" to R.drawable.ic_stock_mrk,
    "MS" to R.drawable.ic_stock_ms,
    "MU" to R.drawable.ic_stock_mu,
    "NEE" to R.drawable.ic_stock_nee,
    "NFLX" to R.drawable.ic_stock_nflx,
    "NKE" to R.drawable.ic_stock_nke,
    "NOW" to R.drawable.ic_stock_now,
    "ORCL" to R.drawable.ic_stock_orcl,
    "OXY" to R.drawable.ic_stock_oxy,
    "PANW" to R.drawable.ic_stock_panw,
    "PEP" to R.drawable.ic_stock_pep,
    "PFE" to R.drawable.ic_stock_pfe,
    "PG" to R.drawable.ic_stock_pg,
    "PLTR" to R.drawable.ic_stock_pltr,
    "PM" to R.drawable.ic_stock_pm,
    "PYPL" to R.drawable.ic_stock_pypl,
    "QCOM" to R.drawable.ic_stock_qcom,
    "RBLX" to R.drawable.ic_stock_rblx,
    "RIVN" to R.drawable.ic_stock_rivn,
    "RTX" to R.drawable.ic_stock_rtx,
    "SBUX" to R.drawable.ic_stock_sbux,
    "SCHW" to R.drawable.ic_stock_schw,
    "SNOW" to R.drawable.ic_stock_snow,
    "SOFI" to R.drawable.ic_stock_sofi,
    "SPOT" to R.drawable.ic_stock_spot,
    "T" to R.drawable.ic_stock_t,
    "TGT" to R.drawable.ic_stock_tgt,
    "TMO" to R.drawable.ic_stock_tmo,
    "TMUS" to R.drawable.ic_stock_tmus,
    "TTWO" to R.drawable.ic_stock_ttwo,
    "TXN" to R.drawable.ic_stock_txn,
    "UBER" to R.drawable.ic_stock_uber,
    "UNH" to R.drawable.ic_stock_unh,
    "UNP" to R.drawable.ic_stock_unp,
    "UPS" to R.drawable.ic_stock_ups,
    "V" to R.drawable.ic_stock_v,
    "VZ" to R.drawable.ic_stock_vz,
    "WFC" to R.drawable.ic_stock_wfc,
    "WMT" to R.drawable.ic_stock_wmt,
    "XOM" to R.drawable.ic_stock_xom,
)

private val lightStockMarks = setOf("AAPL", "GM", "NKE", "PLTR", "RTX", "TTWO")

@Composable
internal fun StockLogo(ticker: String, modifier: Modifier = Modifier, companyName: String? = null) {
    val symbol = ticker.uppercase(Locale.US)
    Box(
        modifier.size(48.dp).clip(RoundedCornerShape(12.dp))
            .background(if (symbol in lightStockMarks) Color(0xFF111111) else Color.White),
        contentAlignment = Alignment.Center,
    ) {
        val logo = when (symbol) {
            "AAPL" -> R.drawable.ic_stock_apple
            "MSFT" -> R.drawable.ic_stock_microsoft
            "TSLA" -> R.drawable.ic_stock_tesla
            "NVDA" -> R.drawable.ic_stock_nvidia
            "GOOGL" -> R.drawable.ic_stock_google
            "AMZN" -> R.drawable.ic_stock_amazon
            else -> suppliedStockLogos[symbol]
        }
        val logoName = companyName ?: when (symbol) {
            "AAPL" -> "Apple Inc."
            "MSFT" -> "Microsoft Corporation"
            "TSLA" -> "Tesla, Inc."
            "NVDA" -> "NVIDIA Corporation"
            "GOOGL" -> "Alphabet Inc."
            "AMZN" -> "Amazon.com, Inc."
            else -> symbol
        }
        if (logo != null) Image(
            painterResource(logo), "$logoName logo",
            Modifier.fillMaxSize().padding(
                if (symbol in listOf("AAPL", "MSFT", "GOOGL")) 9.dp else 0.dp,
            ),
        )
        else Text(
            text = symbol,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.Bold,
            fontSize = if (symbol.length <= 3) 14.sp else 10.sp,
            maxLines = 1,
        )
    }
}
