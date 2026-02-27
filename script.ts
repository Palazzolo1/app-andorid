type CellValue = string | number | boolean;

function main(workbook: ExcelScript.Workbook) {
  // ---------- helpers ----------
  const norm = (v: CellValue | null | undefined): string => {
    if (v === null || v === undefined) return "";
    return String(v).trim();
  };

  const isEmpty = (v: CellValue | null | undefined): boolean => norm(v) === "";

  const parseQty = (v: CellValue | null | undefined): { ok: boolean; qty: number; raw: string } => {
    const raw = norm(v);
    if (raw === "") return { ok: false, qty: NaN, raw: "" };

    if (typeof v === "number") {
      return { ok: isFinite(v) && v > 0, qty: v, raw };
    }
    const txt = raw.replace(",", ".");
    const n = Number(txt);
    return { ok: isFinite(n) && n > 0, qty: n, raw };
  };

  // ---------- 1) encontrar tabla Movimientos por encabezados ----------
  const tablas = workbook.getTables();
  let tablaMov: ExcelScript.Table | undefined;

  for (const t of tablas) {
    const headers = t.getHeaderRowRange().getValues()[0].map(v => String(v).trim().toLowerCase());
    if (headers.includes("tipo") && headers.includes("codigo") && headers.includes("cantidad") && headers.includes("resultado")) {
      tablaMov = t;
      break;
    }
  }
  if (!tablaMov) throw new Error("No se encontró la tabla con columnas Tipo, Codigo, Cantidad y Resultado.");

  const headerRow = tablaMov.getHeaderRowRange().getValues()[0].map(v => String(v).trim().toLowerCase());
  const idxTipo = headerRow.indexOf("tipo");
  const idxCodigo = headerRow.indexOf("codigo");
  const idxCantidad = headerRow.indexOf("cantidad");
  const idxResultado = headerRow.indexOf("resultado");

  // refrescar dataRange / movData siempre que la tabla cambie
  const getMov = () => {
    const dr = tablaMov!.getRangeBetweenHeaderAndTotal();
    const data = dr.getValues() as CellValue[][];
    return { dr, data, rows: dr.getRowCount() };
  };

  let { dr: dataRange, data: movData, rows: rowCount } = getMov();
  if (rowCount === 0) return;

  // ---------- 1.1) LIMPIEZA: si no hay movimientos reales, borrar todo ----------
  // "movimiento real" = alguna de las 3 columnas (Tipo/Codigo/Cantidad) tiene algo.
  let lastInput = -1;
  for (let i = 0; i < rowCount; i++) {
    const t = norm(movData[i][idxTipo]);
    const c = norm(movData[i][idxCodigo]);
    const q = norm(movData[i][idxCantidad]);
    if (t !== "" || c !== "" || q !== "") lastInput = i;
  }

  if (lastInput === -1) {
    // tabla inflada con filas vacías (aunque Resultado tenga basura): borrarlas de una
    tablaMov.deleteRowsAt(0, rowCount);
    return;
  }

  // si hay cola de filas vacías al final, recortarla en bloque (rápido)
  if (lastInput < rowCount - 1) {
    const start = lastInput + 1;
    const count = rowCount - start;
    tablaMov.deleteRowsAt(start, count);

    ({ dr: dataRange, data: movData, rows: rowCount } = getMov());
  }

  // ---------- 2) cargar Stock / Historial / Nivel ----------
  const hojaStock = workbook.getWorksheet("Stock Insumos");
  const hojaHistorial = workbook.getWorksheet("Historial");
  const hojaNivel = workbook.getWorksheet("Nivel de Stock");
  if (!hojaStock || !hojaHistorial) throw new Error("Faltan las hojas 'Stock Insumos' o 'Historial'.");

  const rangoStock = hojaStock.getUsedRange();
  if (!rangoStock) throw new Error("La hoja 'Stock Insumos' está vacía (no hay UsedRange).");

  const stockData = rangoStock.getValues() as CellValue[][];
  const cabStock = stockData[0].map(v => String(v).trim().toLowerCase());

  const idxStockCodigo = cabStock.indexOf("codigo");
  const idxStockCant = cabStock.indexOf("cantidad actual");
  const idxStockNivel = cabStock.indexOf("nivel de stock");

  if (idxStockCodigo < 0 || idxStockCant < 0) {
    throw new Error("En 'Stock Insumos' faltan columnas: 'Codigo' y/o 'Cantidad Actual'.");
  }

  // mapa stock
  const stockMap: Record<string, number> = {};
  for (let i = 1; i < stockData.length; i++) {
    const cod = String(stockData[i][idxStockCodigo] ?? "").trim().toLowerCase();
    if (cod) stockMap[cod] = i;
  }

  // límites
  const limits: Record<string, { min: number; max: number }> = {};
  if (hojaNivel) {
    const r = hojaNivel.getUsedRange();
    if (r) {
      const nivelData = r.getValues() as CellValue[][];
      const cabNivel = nivelData[0].map(v => String(v).trim().toLowerCase());
      const idxNcod = cabNivel.indexOf("codigo");
      const idxMin = cabNivel.indexOf("minimo");
      const idxMax = cabNivel.indexOf("maximo");

      if (idxNcod >= 0 && idxMin >= 0 && idxMax >= 0) {
        for (let i = 1; i < nivelData.length; i++) {
          const cod = String(nivelData[i][idxNcod] ?? "").trim().toLowerCase();
          const min = Number(String(nivelData[i][idxMin] ?? "").replace(",", "."));
          const max = Number(String(nivelData[i][idxMax] ?? "").replace(",", "."));
          if (cod) limits[cod] = { min: isFinite(min) ? min : 0, max: isFinite(max) ? max : 0 };
        }
      }
    }
  }

  const newHistRows: CellValue[][] = [];
  const rowsToDelete: number[] = [];
  let stockChanged = false;

  // ---------- 3) procesar movimientos ----------
  const tiposValidos = ["ENTRADA", "SALIDA", "FABRICACION", "VENTA"];

  for (let i = rowCount - 1; i >= 0; i--) {
    const tipoRaw = norm(movData[i][idxTipo]).toUpperCase();
    const codigoRaw = norm(movData[i][idxCodigo]);
    const cantidadRaw = movData[i][idxCantidad];
    const resultadoPrev = norm(movData[i][idxResultado]).toUpperCase();

    const filaVacia = (tipoRaw === "" && codigoRaw === "" && isEmpty(cantidadRaw));
    if (filaVacia) {
      // IMPORTANTÍSIMO: no loguear, no marcar error, y borrar estas filas basura
      rowsToDelete.push(i);
      continue;
    }

    // Si por algún motivo quedó una fila "OK" en la tabla, limpiarla sin reprocesar (evita doble descuento)
    if (resultadoPrev === "OK") {
      rowsToDelete.push(i);
      continue;
    }

    let resultado = "OK";

    if (!tiposValidos.includes(tipoRaw)) {
      resultado = `ERROR: Tipo inválido (${tipoRaw})`;
    }

    if (resultado === "OK" && codigoRaw === "") {
      resultado = "ERROR: Código vacío";
    }

    const qtyParsed = parseQty(cantidadRaw);
    if (resultado === "OK" && !qtyParsed.ok) {
      resultado = "ERROR: Cantidad inválida";
    }

    const codigoKey = codigoRaw.toLowerCase();
    const stockRow = stockMap[codigoKey];

    let stockActual = 0;
    if (resultado === "OK") {
      if (stockRow === undefined) {
        resultado = `ERROR: Codigo '${codigoRaw}' no encontrado`;
      } else {
        const val = stockData[stockRow][idxStockCant];
        const n = Number(String(val ?? "").replace(",", "."));
        stockActual = isFinite(n) ? n : 0;
      }
    }

    if (resultado === "OK") {
      const delta = (tipoRaw === "ENTRADA" || tipoRaw === "FABRICACION") ? qtyParsed.qty : -qtyParsed.qty;
      const nuevoStock = stockActual + delta;

      if (nuevoStock < 0) {
        resultado = `ERROR: Stock insuficiente (actual ${stockActual})`;
      } else {
        stockData[stockRow][idxStockCant] = nuevoStock;
        stockChanged = true;

        if (idxStockNivel >= 0) {
          const lim = limits[codigoKey];
          let nivelStr = "";
          if (lim) {
            if (nuevoStock < lim.min) nivelStr = "BAJO";
            else if (nuevoStock > lim.max) nivelStr = "ALTO";
            else nivelStr = "MEDIO";
          }
          stockData[stockRow][idxStockNivel] = nivelStr;
        }

        // si fue OK, se borra de Movimientos
        rowsToDelete.push(i);
      }
    }

    // escribir resultado en la tabla (para errores, se queda visible)
    movData[i][idxResultado] = resultado;

    // log historial SOLO para filas con datos (esta fila NO es vacía)
    const qtyForHist: CellValue = qtyParsed.ok ? qtyParsed.qty : qtyParsed.raw;
    const ahoraISO: CellValue = new Date().toISOString();
    newHistRows.push([ahoraISO, tipoRaw, codigoRaw, qtyForHist, resultado]);
  }

  // ---------- 4) aplicar cambios ----------
  // Movimientos (resultados)
  dataRange.setValues(movData);

  // Stock: actualizar SOLO columnas que tocamos (no pisar otras columnas)
  if (stockChanged) {
    const colCant: CellValue[][] = stockData.map(r => [r[idxStockCant] as CellValue]);
    rangoStock.getColumn(idxStockCant).setValues(colCant);

    if (idxStockNivel >= 0) {
      const colNivel: CellValue[][] = stockData.map(r => [r[idxStockNivel] as CellValue]);
      rangoStock.getColumn(idxStockNivel).setValues(colNivel);
    }
  }

  // Historial: append en bloque (sin usar filas vacías)
  if (newHistRows.length > 0) {
    newHistRows.reverse(); // se generaron de abajo hacia arriba

    const used = hojaHistorial.getUsedRange();
    let startRow = 0;

    if (used) {
      const vals = used.getValues() as CellValue[][];
      // buscar última fila con algo en la col A (FechaHora)
      let last = -1;
      for (let r = vals.length - 1; r >= 0; r--) {
        if (String(vals[r][0] ?? "").trim() !== "") { last = r; break; }
      }
      startRow = last + 1;
    }

    const range = hojaHistorial.getRangeByIndexes(startRow, 0, newHistRows.length, newHistRows[0].length);
    range.setValues(newHistRows);
  }

  // Borrar filas (descendente para no romper índices)
  if (rowsToDelete.length > 0) {
    rowsToDelete.sort((a, b) => b - a);
    for (const idx of rowsToDelete) {
      tablaMov.deleteRowsAt(idx, 1);
    }
  }
}
