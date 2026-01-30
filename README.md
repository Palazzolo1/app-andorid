# Control de Stock Android

Aplicación Android nativa (Jetpack Compose) para registrar inventario y ventas acompañadas de una foto como comprobante.

## Características
- Resumen de inventario con unidades disponibles e ingresos estimados.
- Registro rápido de ventas seleccionando el producto, cantidad y notas adicionales.
- Captura de foto desde la cámara usando la API `ActivityResultContracts.TakePicturePreview`.
- Historial en forma de tarjetas que muestran total vendido, fecha, notas y miniatura de la foto.

## Requisitos
- Android Studio Ladybug o superior.
- Gradle 8.5+
- Android SDK 34.

## Ejecución
1. Clona el repositorio y ábrelo con Android Studio.
2. Sincroniza el proyecto para descargar dependencias.
3. Compila y ejecuta la app en un dispositivo físico (se necesita cámara) o emulador con cámara.

## Permisos
La app solicita permiso de cámara para poder capturar las fotos durante el registro de cada venta.
