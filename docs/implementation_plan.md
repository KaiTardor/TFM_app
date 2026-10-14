# Plan de trabajo pendiente

Este documento contiene solo pendientes, no un historial de cambios.
Las comprobaciones manuales en dispositivo se consideran realizadas por el autor;
no se mantiene una matriz general de pruebas por repetir.

## E10. Medicion de arranque

StrictMode esta activado en debug para registrar lecturas y escrituras de disco
en Main. Se ha eliminado la preferencia del debounce de Galeria, que no evitaba
la sincronizacion inicial y solo necesitaba estado en memoria.

Falta medir un arranque en frio y revisar los avisos de preferencias de apariencia,
consentimiento y factories. No introducir estados de carga ni otra arquitectura
sin un coste observado. Esta medicion no se ha realizado desde el entorno de desarrollo.

## Verificacion puntual

Los cambios nuevos de recuperacion requieren una comprobacion concreta, no repetir
toda la aplicacion: capturas sin callback con recuperar/descartar, registro fallido
y trabajo interrumpido sin reenvio automatico. Las pruebas instrumentales conservadas
cubren estos casos y las transacciones Room; compilarlas no equivale a ejecutarlas.

## Decisiones de producto

- Decidir si se mantiene Gemini, sin verificador documentado util en la API estandar.
- Esperar al entrenamiento y contrato del modelo local.

No se requiere Hilt, mas modulos ni C2PA local dentro del alcance actual.
Consultar el [README](README.md) para funcionalidades y pruebas esenciales.
