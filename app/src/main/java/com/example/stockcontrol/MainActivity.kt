package com.example.stockcontrol

import android.graphics.Bitmap
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import com.example.stockcontrol.ui.theme.StockControlTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

class MainActivity : ComponentActivity() {
    private val viewModel: StockViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            StockControlTheme {
                StockScreen(viewModel = viewModel)
            }
        }
    }
}

data class StockItem(
    val id: Int,
    val name: String,
    val quantity: Int,
    val unitPrice: Double
)

data class SaleRecord(
    val id: Long,
    val itemName: String,
    val quantity: Int,
    val totalPrice: Double,
    val timestamp: LocalDateTime,
    val notes: String,
    val photo: Bitmap?
)

data class StockUiState(
    val stockItems: List<StockItem> = emptyList(),
    val sales: List<SaleRecord> = emptyList()
) {
    val availableUnits: Int = stockItems.sumOf { it.quantity }
    val totalRevenue: Double = sales.sumOf { it.totalPrice }
}

class StockViewModel : ViewModel() {
    private val _uiState = MutableStateFlow(
        StockUiState(
            stockItems = listOf(
                StockItem(1, "Camisas de algodón", 25, 15.0),
                StockItem(2, "Pantalones deportivos", 30, 22.5),
                StockItem(3, "Zapatillas urbanas", 18, 45.0)
            )
        )
    )
    val uiState: StateFlow<StockUiState> = _uiState.asStateFlow()

    fun registerSale(itemId: Int, quantity: Int, notes: String, photo: Bitmap?) {
        if (quantity <= 0) return
        val current = _uiState.value
        val product = current.stockItems.firstOrNull { it.id == itemId } ?: return
        val newQuantity = (product.quantity - quantity).coerceAtLeast(0)
        val updatedItems = current.stockItems.map { item ->
            if (item.id == itemId) item.copy(quantity = newQuantity) else item
        }
        val sale = SaleRecord(
            id = System.currentTimeMillis(),
            itemName = product.name,
            quantity = quantity,
            totalPrice = quantity * product.unitPrice,
            timestamp = LocalDateTime.now(),
            notes = notes,
            photo = photo
        )
        _uiState.update {
            it.copy(
                stockItems = updatedItems,
                sales = listOf(sale) + it.sales
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StockScreen(viewModel: StockViewModel) {
    val uiState by viewModel.uiState.collectAsState()
    var addSaleDialogVisible by remember { mutableStateOf(false) }
    var capturedPhoto by remember { mutableStateOf<Bitmap?>(null) }

    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicturePreview()) { bitmap ->
        capturedPhoto = bitmap
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(text = stringResource(id = R.string.app_name)) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary
                )
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { addSaleDialogVisible = true }) {
                Icon(
                    painter = painterResource(android.R.drawable.ic_input_add),
                    contentDescription = stringResource(id = R.string.add_sale)
                )
            }
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                StockSummaryCard(uiState)
            }
            item {
                Text(
                    text = stringResource(id = R.string.sales_title),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(vertical = 8.dp)
                )
            }
            items(uiState.sales, key = { it.id }) { sale ->
                SaleCard(saleRecord = sale)
            }
            if (uiState.sales.isEmpty()) {
                item {
                    EmptyState()
                }
            }
        }
    }

    if (addSaleDialogVisible) {
        AddSaleDialog(
            stockItems = uiState.stockItems,
            capturedPhoto = capturedPhoto,
            onCapturePhoto = { cameraLauncher.launch(null) },
            onDismiss = {
                capturedPhoto = null
                addSaleDialogVisible = false
            },
            onSave = { itemId, qty, notes ->
                viewModel.registerSale(itemId, qty, notes, capturedPhoto)
                capturedPhoto = null
                addSaleDialogVisible = false
            }
        )
    }
}

@Composable
fun StockSummaryCard(uiState: StockUiState) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = stringResource(id = R.string.stock_summary_title),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                Column {
                    Text(text = "Unidades disponibles", fontWeight = FontWeight.Bold)
                    Text(text = uiState.availableUnits.toString(), style = MaterialTheme.typography.titleLarge)
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(text = "Ingresos estimados", fontWeight = FontWeight.Bold)
                    Text(
                        text = "${'$'}${"%.2f".format(uiState.totalRevenue)}",
                        style = MaterialTheme.typography.titleLarge
                    )
                }
            }
        }
    }
}

@Composable
fun SaleCard(saleRecord: SaleRecord) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(text = saleRecord.itemName, style = MaterialTheme.typography.titleLarge)
            Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                Column {
                    Text(text = "Cantidad", style = MaterialTheme.typography.labelLarge)
                    Text(text = saleRecord.quantity.toString())
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(text = "Total", style = MaterialTheme.typography.labelLarge)
                    Text(text = "${'$'}${"%.2f".format(saleRecord.totalPrice)}")
                }
            }
            Text(
                text = saleRecord.timestamp.format(DateTimeFormatter.ofPattern("dd MMM yyyy - HH:mm")),
                style = MaterialTheme.typography.bodyMedium,
                color = Color.Gray
            )
            if (saleRecord.notes.isNotEmpty()) {
                Text(text = saleRecord.notes)
            }
            saleRecord.photo?.let { bitmap ->
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(180.dp)
                        .background(Color(0xFFE7ECEF), RoundedCornerShape(12.dp)),
                    contentScale = ContentScale.Crop
                )
            }
        }
    }
}

@Composable
fun EmptyState() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier
                .size(80.dp)
                .background(Color(0xFFE7ECEF), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                painter = painterResource(android.R.drawable.ic_menu_camera),
                contentDescription = null
            )
        }
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = "Aún no registraste ventas",
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.bodyLarge,
            color = Color.Gray
        )
    }
}

@Composable
fun AddSaleDialog(
    stockItems: List<StockItem>,
    capturedPhoto: Bitmap?,
    onCapturePhoto: () -> Unit,
    onDismiss: () -> Unit,
    onSave: (itemId: Int, quantity: Int, notes: String) -> Unit
) {
    var selectedItemIndex by remember { mutableStateOf(0) }
    var quantityInput by remember { mutableStateOf(TextFieldValue("1")) }
    var notes by remember { mutableStateOf(TextFieldValue("")) }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = {
                val selectedItem = stockItems.getOrNull(selectedItemIndex) ?: return@TextButton
                val quantity = quantityInput.text.toIntOrNull() ?: 0
                onSave(selectedItem.id, quantity, notes.text)
            }) {
                Text(text = stringResource(id = R.string.save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(id = R.string.cancel))
            }
        },
        title = { Text(text = stringResource(id = R.string.add_sale)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (stockItems.isNotEmpty()) {
                    Text(text = "Producto: ${stockItems[selectedItemIndex].name}")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        IconButton(onClick = { if (selectedItemIndex > 0) selectedItemIndex-- }) {
                            Icon(painterResource(android.R.drawable.ic_media_previous), contentDescription = "Anterior")
                        }
                        IconButton(onClick = { if (selectedItemIndex < stockItems.lastIndex) selectedItemIndex++ }) {
                            Icon(painterResource(android.R.drawable.ic_media_next), contentDescription = "Siguiente")
                        }
                    }
                }
                OutlinedTextField(
                    value = quantityInput,
                    onValueChange = { quantityInput = it },
                    label = { Text(text = "Cantidad") }
                )
                OutlinedTextField(
                    value = notes,
                    onValueChange = { notes = it },
                    label = { Text(text = "Notas (cliente, método, etc.)") }
                )
                TextButton(onClick = onCapturePhoto) {
                    Text(text = stringResource(id = R.string.capture_photo))
                }
                capturedPhoto?.let { bitmap ->
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = null,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(160.dp)
                            .background(Color(0xFFE7ECEF), RoundedCornerShape(12.dp)),
                        contentScale = ContentScale.Crop
                    )
                }
            }
        }
    )
}
