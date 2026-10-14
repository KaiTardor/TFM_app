# Navegación de BeSharp

La aplicación utiliza Navigation Compose con rutas serializables definidas en
`ui/navigation/AppRoutes.kt`. Los detalles se identifican mediante `photoId` o
`assessmentId`, sin pasar entidades Room como argumentos de navegación.

En ventanas de ancho expandido se muestra una barra lateral `NavigationRail`.
En anchos compacto y medio se utiliza la barra inferior.

## Destinos

| Ruta | Función |
|---|---|
| `Gallery` | Galería inicial, calendario, lista y selección de imágenes |
| `PhotoDetail(photoId)` | Detalle de una imagen de la galería |
| `AssessmentResult(assessmentId)` | Resultado de una evaluación concreta |
| `Analysis` | Preparación y programación de un lote |
| `Settings` | Menú de ajustes y sus subpantallas |
| `Legal` | Datos, privacidad y consentimiento remoto |

Las subpantallas de Ajustes usan `SettingsPage`; no son destinos independientes
del `NavHost`. El botón Atrás vuelve al menú o, desde Cómo funciona BeSharp, a
Acerca de BeSharp.

## Galería y permisos

Galería es el destino inicial. Permite alternar entre calendario y lista,
filtrar por Todas, Sin analizar o Analizadas, y abrir el detalle de una foto.

La aplicación solicita permisos de fotos según la versión de Android. En
Android 14 o superior admite acceso completo o parcial. Galería y Ajustes leen
el mismo estado de permisos; al volver de Ajustes se vuelve a comprobar el
acceso. Un cambio de alcance cancela la sincronización anterior y permite
actualizar inmediatamente sin aplicar el límite habitual de cinco minutos.

Con acceso parcial, las referencias MediaStore no autorizadas quedan ocultas,
no se consideran fotos borradas. La UI ofrece solicitar acceso o abrir Ajustes
cuando corresponde. El comportamiento real del diálogo del sistema sigue
pendiente de verificación conectada.

## Preparación de un análisis

La acción central abre Analizar. Esta pantalla prepara peticiones, no presenta
el resultado final ni espera a que terminen los proveedores.

El botón principal muestra siempre ANALIZAR. Los contadores de selección usan
un formato numérico sencillo, sin variantes por singular o plural.

1. Seleccionar hasta 10 imágenes desde la galería interna o capturar una foto.
2. Elegir uno o varios proveedores configurados.
3. Aceptar el consentimiento remoto si no cubre los destinos y entradas actuales.
4. Registrar los trabajos y programarlos mediante WorkManager.
5. Volver automáticamente a Galería cuando el lote queda programado.

La selección temporal en Galería permite confirmar o cancelar. Al salir, se
restauran su filtro y modo de vista anteriores. Los borradores de selección se
restauran con `SavedStateHandle`; la validación de URI elimina solo las que no
se pueden abrir, sin sobrescribir cambios posteriores.

Un envío invalida las lecturas de imágenes pendientes, de modo que no puedan
añadir contenido al lote enviado ni a la siguiente selección.

### Captura y recuperación

La cámara externa recibe una URI FileProvider de un archivo privado reservado
con nombre único. La URI, el nombre y la ruta pendientes se conservan mediante
estado guardable de Compose.

- `.pending`: la captura aún no se ha confirmado; se conserva.
- `.ready`: la cámara confirmó un archivo con contenido; puede recuperarse si
  su registro en Room quedó interrumpido.
- `.cancelled`: cancelación explícita; el borrado puede reintentarse al arrancar.

Una captura declarada exitosa pero vacía muestra un error. Un callback tardío no
puede cancelar una foto ya completada o registrada. Los archivos antiguos sin
marcador no se borran automáticamente. Las comprobaciones generales en dispositivo
han sido realizadas por el autor.

Mientras la URI de captura siga pendiente no se inicia otra cámara, incluso al
restaurar el estado de pantalla. Si se recibió el resultado pero falló su
procesamiento local, se ofrece reintentar ese mismo resultado. Cuando falta el
callback, se ofrecen Recuperar y Descartar tras confirmar que la camara externa
esta cerrada. La decision revoca los permisos temporales y separa el registro
del launcher para que un callback tardio no afecte a la siguiente captura.
No se deduce abandono ni se borra el archivo automaticamente. Las capturas con
marcador tambien se descubren desde disco si no queda estado de pantalla.
Un fallo de registro conserva la foto y ofrece reintento sin exigir MediaStore.

## Resultados y avisos

Mientras existan evaluaciones pendientes, Galería muestra un aviso persistente.
Si una evaluación termina con la aplicación abierta, puede mostrar un aviso con
acceso al resultado. Fuera de primer plano, el Worker puede publicar una
notificación que abre `AssessmentResult`.

El detalle presenta la resolución conjunta, la imagen, las señales agrupadas y
los datos por proveedor. Los fallos parciales se muestran sin ocultar resultados
válidos de otras fuentes. Desde el detalle se puede compartir la imagen o
preparar otra evaluación.

Las fuentes muestran su nivel de verificación para distinguir señales reportadas
de verificaciones remotas o locales. Las secciones de cada trabajo usan su ID
como clave de composición, sin heredar expansión de otra evaluación.

Los destinos consultan la foto o evaluación por su identificador y muestran
carga, error o contenido no disponible en lugar de quedar vacíos. El detalle de
una evaluación no depende del límite de entradas del historial. Sigue pendiente
la verificación en dispositivo de esos flujos.

Si falla la carga real de la imagen, el detalle muestra una explicación y una
acción de reintento. Compartir y Analizar quedan deshabilitados mientras no se
pueda abrir; los resultados y señales guardados siguen visibles.

Cuando hay más de una evaluación completada para la misma URI, se solicita
elegir entre el resultado anterior y el nuevo. Ambos se conservan hasta la
elección; después se eliminan las demás evaluaciones completadas de esa imagen.

## Ajustes

- Proveedores: claves de Gemini y OpenAI, catálogo y modelo de Gemini, enlace al
  verificador web de OpenAI y configuración de una API personalizada.
- Personalización: tema del sistema, claro u oscuro, y tipografía del sistema.
- Notificaciones: activación general y preferencias para resultados y errores.
- Permisos: estado de acceso y acceso a los ajustes de Android.
- Acerca de BeSharp: versión, privacidad y Cómo funciona BeSharp.

OpenAI Content Provenance no tiene selección de modelo GPT. Gemini continúa
disponible, pero su API estándar no ofrece un verificador de procedencia
documentado y normalmente responde `NOT_CHECKED`.

El modelo de Gemini se selecciona como borrador y se confirma con Guardar.
Cargar su catálogo no modifica la configuración ni sustituye la clave.

La selección de proveedores obtiene nombre y modelo del mismo descriptor que
utiliza el análisis. Los proveedores actuales son remotos; el consentimiento
explica el envío sin repetir una etiqueta de ubicación en cada opción.

## Consentimiento y notificaciones

El consentimiento identifica el proveedor, destino, capacidad y tipo de archivo.
Gemini y OpenAI reciben originales; la API personalizada recibe una copia JPEG
normalizada sin EXIF. Consultar la página legal desde el diálogo permite volver
a la preparación del consentimiento.

Nombres, destinos y tipos proceden de una única instantánea. Si la configuración
cambia mientras el diálogo está abierto, la confirmación no autoriza los nuevos
destinos sin mostrarlos de nuevo. Guardar y revocar se ejecutan fuera de Main y
los botones del diálogo se bloquean mientras termina la operación.

Si falla el guardado de la revocación, el diálogo muestra un error y no anuncia
éxito. Tras persistirla, se solicita cancelar los trabajos remotos. Revocar no
retira imágenes ya recibidas por un servicio externo.

El intento de cancelación forma parte de la operación protegida de revocación y
no se pospone a un scope del ViewModel que pueda haberse destruido.

El permiso de notificaciones no se solicita al arrancar. En Android 13 o
superior se pide al activar Notificaciones de resultados. Rechazarlo no impide
analizar; pueden configurarse por separado los avisos de resultados y errores.

Las preferencias de resultado y error se seleccionan según los estados de los
trabajos: una evaluación inconclusa sin fallos es una ejecución completada; un
fallo o cancelación activa el criterio de error aunque otra fuente aporte evidencia.

## Flujo resumido

```text
Galería o detalle -> Analizar -> selección o captura -> proveedores
    -> consentimiento -> Room y WorkManager -> regreso a Galería
    -> trabajos terminales -> resolución versionada
    -> aviso o notificación -> detalle del resultado
    -> elección del resultado conservado si ya existía otro
```

Para límites, capacidades y estado de pruebas, consultar el [README](README.md).
