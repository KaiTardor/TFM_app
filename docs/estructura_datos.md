# Estructura de datos de BeSharp

Room usa una linea base inicial limpia, version 1. No existen migraciones de
PixDate porque la aplicacion todavia no se ha distribuido y no hay datos de
usuarios que conservar. Room no almacena bytes de imagen ni credenciales.

La base se denomina `besharp_database` y exporta su esquema en `app/schemas/`.
No se usa `fallbackToDestructiveMigration()`. Si cambia el esquema de esta
versión no publicada, es necesario borrar los datos o reinstalar la aplicación.

## photos

Referencia una imagen detectada en MediaStore o capturada por BeSharp.

| Campo | Tipo | Descripcion |
|---|---|---|
| `photoId` | Long | Clave primaria autogenerada |
| `contentUri` | String | URI unica de la imagen |
| `dateTaken` | Long | Fecha usada por calendario |
| `displayName` | String | Nombre visible |
| `mimeType` | String? | MIME original |
| `isVisible` | Boolean | Visible con el permiso MediaStore actual |
| `createdAt` | Long | Creacion del registro |
| `updatedAt` | Long | Ultima actualizacion |

La imagen original permanece en MediaStore o en el directorio privado de fotos
capturadas. Con acceso parcial, las fotos no autorizadas se conservan en Room
pero quedan ocultas hasta recuperar visibilidad; no se confunden con borrados.

`dateTaken` usa la fecha de MediaStore con fallback a `DATE_ADDED`. Para las
capturas privadas se utiliza la fecha de modificación del archivo, también
cuando se recuperan después de un reinicio.

## image_assessments

Es la unidad principal del producto. Una evaluacion agrupa todos los trabajos
solicitados para una imagen y conserva una unica resolucion final.

| Campo | Tipo | Descripcion |
|---|---|---|
| `assessmentId` | Long | Clave primaria |
| `imageUri` | String | URI de la imagen |
| `imageDisplayName` | String | Nombre mostrado |
| `status` | String | `PREPARING`, `ACTIVE` o `COMPLETED` |
| `expectedJobCount` | Int | Numero de trabajos con el que se sella el lote |
| `createdAt` | Long | Creacion |
| `completedAt` | Long? | Finalizacion |
| `finalResolution` | String? | Resolucion agregada |
| `summary` | String? | Explicacion breve |
| `resolutionReason` | String? | Regla aplicada |
| `resolutionPolicyVersion` | Int? | Version del motor |
| `decisiveSignalIdsJson` | String | IDs de señales decisivas |
| `conflictingSignalIdsJson` | String | IDs de señales contradictorias |

Resoluciones posibles:

- `AI_GENERATED_OR_EDITED`.
- `POSSIBLY_SYNTHETIC`.
- `VERIFIED_CAPTURE`.
- `NO_SUFFICIENT_EVIDENCE`.
- `INCONCLUSIVE`.

La evaluacion y todos sus trabajos se insertan en una transaccion con estado
`PREPARING`. Solo pasa a `ACTIVE` cuando el numero de filas coincide con
`expectedJobCount`. El ultimo trabajo terminal intenta cerrarla mediante
compare-and-set y vuelve a comprobar que el lote este completo. Las evaluaciones
listas se reconcilian al iniciar UI o Worker tras una interrupcion del proceso.

Puede haber varias evaluaciones para la misma URI mientras se procesa un nuevo
análisis o se espera la elección del usuario. La política de conservar un único
resultado completado se aplica después de esa elección, no mediante una
restricción única sobre `imageUri`.

## analysis_jobs

Cada fila representa una capacidad concreta de un proveedor dentro de una
evaluacion. OpenAI Content Provenance crea un trabajo de procedencia; la API
personalizada aporta estimaciones visuales o señales reportadas mediante su contrato.

| Campo | Tipo | Descripcion |
|---|---|---|
| `jobId` | Long | Clave primaria |
| `assessmentId` | Long | FK a `image_assessments` |
| `providerId` | String | Identificador estable |
| `providerName` | String | Nombre visible |
| `modelId` | String | Modelo o capacidad |
| `capability` | String | Visual, procedencia o modelo local futuro |
| `inputType` | String | `NORMALIZED_IMAGE` u `ORIGINAL_FILE` |
| `executionId` | String | Identificador estable de ejecución; permite deduplicación en la API custom |
| `destinationFingerprint` | String | Destino consentido |
| `customEndpointUrl` | String? | Snapshot custom sin credenciales |
| `customParametersJson` | String? | Parametros custom no sensibles |
| `status` | String | Estado persistente |
| `createdAt` | Long | Creacion |
| `startedAt` | Long? | Inicio |
| `completedAt` | Long? | Finalizacion |
| `errorMessage` | String? | Mensaje normalizado |
| `errorCategory` | String? | Categoria controlada |
| `httpStatus` | Int? | Estado HTTP validado |

Estados de trabajo: `QUEUED`, `RUNNING`, `SUCCEEDED`, `FAILED` y `CANCELLED`.
Las transiciones se realizan con compare-and-set. Resultado y cambio a
`SUCCEEDED` comparten una transaccion; una cancelacion previa impide insertar un
resultado tardio.

La reconciliación distingue trabajo ausente, activo y finalizado en WorkManager.
No encola otra vez un trabajo activo. Si el sistema ya terminó pero Room conserva
estado activo, intenta cerrar el trabajo con un fallo de almacenamiento local,
sin inventar una respuesta del proveedor. Si falta el registro del sistema,
cancela el trabajo local sin reenviarlo: no distingue uno nunca programado de
otro cuyo historial fue podado. Un Worker restaurado en `RUNNING` se cancela
tambien; solo los reintentos devueltos explicitamente a `QUEUED` permiten reenvio.

La URI y el nombre se leen desde `image_assessments`; no se duplican en cada
trabajo. Los campos de proveedor y configuracion custom si son snapshots
deliberados de la ejecucion solicitada.

Los tokens se leen del almacén cifrado al ejecutar, no se incluyen en el
snapshot. Cambiar o eliminar una credencial cancela los trabajos asociados;
cambiar el token custom también los cancela aunque el endpoint sea el mismo.
El identificador de ejecución no implica por sí solo idempotencia de todos los
servicios externos.

## authenticity_results

Salida normalizada 1:1 de un trabajo completado.

| Campo | Tipo | Descripcion |
|---|---|---|
| `resultId` | Long | Clave primaria |
| `jobId` | Long | FK unica a `analysis_jobs` |
| `verdict` | String | Estimacion individual |
| `syntheticProbability` | Int? | Estimacion entre 0 y 100 |
| `evidenceType` | String | Tipo principal del resultado |
| `verificationLevel` | String | Nivel de verificacion |
| `summary` | String | Explicacion breve |
| `indicatorsJson` | String | Lista de indicadores o estados de señales |
| `analyzedAt` | Long | Fecha del analisis |
| `durationMillis` | Long | Duracion |
| `origin` | String | API directa o analisis local |

Un resultado individual no es la resolucion final de la imagen.

OpenAI Content Provenance guarda `syntheticProbability = null`.
La API personalizada puede devolver un porcentaje visual, pero no se calcula
una media entre proveedores. Solo resultados de capacidades visuales o del
futuro clasificador local alimentan la rama de estimaciones del motor.

## evidence_signals

Relacion 1:N con `authenticity_results` mediante su `jobId` unico. Permite que
una respuesta contenga C2PA y SynthID simultaneamente.

| Campo | Tipo | Descripcion |
|---|---|---|
| `signalId` | String | ID unico persistido |
| `jobId` | Long | FK al resultado del trabajo |
| `type` | String | C2PA, SynthID, visual, metadatos o modelo local |
| `outcome` | String | Detectada, no detectada, inconclusa o no comprobada |
| `verificationLevel` | String | Local, remota, reportada o visual |
| `source` | String | Fuente de la señal |
| `claim` | String | Generacion, edicion, captura o desconocida |
| `issuer` | String? | Emisor, cuando existe |
| `model` | String? | Modelo de origen, cuando existe |
| `generatedAt` | Long? | Fecha declarada de generacion |
| `explanation` | String? | Explicacion acotada |
| `validationState` | String? | Estado de validacion C2PA |

Niveles de verificacion:

- `LOCALLY_VERIFIED`.
- `REMOTE_VERIFIED`.
- `PROVIDER_REPORTED`.
- `VISUAL_ESTIMATE`.

Los niveles describen cómo se obtuvo la evidencia, no su certeza absoluta.
Las señales de procedencia custom son `PROVIDER_REPORTED`. Las señales
del endpoint específico de OpenAI son `REMOTE_VERIFIED`, con cobertura limitada
a sus señales compatibles.

La política de resolución actual tiene versión 2. Puede confirmar generación
mediante evidencia local o remota verificada; las señales reportadas producen
como máximo posible contenido sintético. No detectar señales nunca demuestra
autenticidad. El modelo local sigue pendiente de entrenamiento y de definir su
contrato de integración. Ni ese modelo ni la verificación C2PA local están integrados.

## Entradas de imagen

- La API custom visual recibe una copia JPEG de hasta 1600 px sin EXIF.
- OpenAI Content Provenance recibe un temporal privado del archivo original,
  limitado a 50 MiB y eliminado siempre al terminar.
- Los proveedores normalizados de una evaluacion reutilizan un JPEG privado. El
  cache se elimina al cerrar la evaluación, también desde cancelación o
  reconciliación, sin depender de publicar una notificación. Los restos se
  eliminan al arrancar el proceso.

La limpieza inicial se realiza en IO y se coordina con un bloqueo de
inicialización que comparten los preparadores. Estos esperan a que termine
antes de crear copias, evitando que una limpieza asíncrona borre archivos nuevos.
- Los analisis intensivos se serializan para limitar picos de bitmap, Base64 y red.
- OpenAI transmite el original por `RequestBody` basado en fichero.

Los fallos de acceso al original usan `SOURCE_UNAVAILABLE`; los fallos de la copia
privada usan `LOCAL_STORAGE`. No se reintentan como indisponibilidad remota.
Formato o archivo vacío son entrada no válida, y el límite excesivo conserva
`FILE_TOO_LARGE`. Los parsers comparten lectura estricta de texto y contenedores;
OpenAI rechaza tipos/outcomes no documentados y las fechas ISO malformadas no se
descartan silenciosamente. Los errores de parser no adjuntan el cuerpo remoto.

## Datos fuera de Room

- API keys y tokens: AES/GCM con clave no exportable en Android Keystore.
- Configuracion no sensible: SharedPreferences.
- Consentimiento remoto version 3: ligado a proveedor, destino, capacidad y tipo
  de entrada. Distingue originales de entradas normalizadas.
- Temporales de procedencia: cache privada, borrada al finalizar o fallar.
- Trabajos del sistema: WorkManager coordinado con los estados de Room.
- Dependencias de aplicacion: AppContainer unico compartido por Compose y Worker.
- Dominio: veredictos, señales y resoluciones viven en `domain/model`; el motor
  no depende de Room, Android ni proveedores HTTP.
- Aplicacion: casos de uso separados preparan/cancelan lotes y reconcilian
  WorkManager con Room.
- Consultas: AnalysisHistoryRepository construye proyecciones de lectura sin
  mezclar su mapeo con los comandos de AnalysisQueueRepository.
- El historial se consulta por evaluación o por su ID. La proyección de cada
  trabajo conserva estado, proveedor, error y resultado; no repite los datos de
  imagen y fechas que ya pertenecen a su evaluación. Room mantiene los snapshots
  completos del trabajo y el modelo realmente utilizado.
- Los IDs decisivos siguen guardados en `image_assessments` y en el resultado del
  motor. La proyección para UI no los deserializa porque no tiene consumidores;
  sí expone los IDs de conflicto que se muestran en el detalle.
- Presentacion: Galería y detalle usan directamente `GalleryPhoto`, sin una copia
  idéntica para UI. Las evaluaciones sí tienen modelos de presentación con estados
  propios; los composables no reciben entidades Room.
- MediaStore: GalleryRepository sincroniza inserciones, cambios de metadatos y
  borrados por lotes después de consultar las imágenes accesibles. No se trata de
  una consulta incremental basada en un token de cambios de MediaStore.

La base de datos, preferencias sensibles, credenciales, imagenes y temporales se
excluyen del backup de Android.

## Capturas privadas y recuperación

`filesDir/photos` contiene las fotos capturadas y sus marcadores de estado:

| Marcador | Significado | Tratamiento |
|---|---|---|
| `.pending` | Captura reservada sin confirmación | Se conserva; la cámara puede seguir escribiendo |
| `.ready` | Captura confirmada con contenido | Se registra en Room y se retira el marcador tras éxito |
| `.cancelled` | Cancelación explícita | Se borra el archivo; si falla, se conserva para reintentar |

Cada captura reserva un nombre único. Al arrancar se recuperan las `.ready` y
se procesan las cancelaciones. Un fallo individual no bloquea las demás fotos;
el marcador fallido permanece y se informa del fallo parcial. La cancelación
de una corrutina se propaga sin confirmar operaciones incompletas.

Una foto completada o registrada no se elimina por un callback de cancelación
tardío. No se deduce abandono por antigüedad, tamaño de un archivo pendiente o
ausencia de una fila en Room. Los archivos antiguos sin marcador se conservan.
El directorio completo se excluye del backup y de la transferencia de datos.

Analisis descubre los marcadores en disco y ofrece recuperar o descartar una
pendiente tras confirmar el cierre de la camara. Los errores de borrado se
propagan para permitir reintento. Se retira `.pending` antes de `.ready`, para
no ofrecer descartar una foto ya registrada si falla la limpieza. Registro y
recuperacion automatica se serializan en el repositorio.

El redimensionado visual comparte un cálculo de dimensiones positivas que no
amplía imágenes pequeñas. BitmapFactory e ImageDecoder utilizan la misma regla;
el fallback decodifica la copia privada y no vuelve a abrir el original.

## Estado transitorio de presentación

Los ViewModels exponen `StateFlow` y la UI observa con ciclo de vida. La selección
de análisis y el estado del calendario usan `SavedStateHandle`; los secretos no
se guardan ahí. Las validaciones llevan una versión de configuración para
ignorar respuestas obsoletas, incluso cuando llega una petición anterior
después de otra más reciente.

La validación de URI restauradas retira solo las inválidas de la selección
vigente. Enviar un lote invalida las lecturas de imágenes pendientes. Los cambios
de permisos invalidan la sincronización de Galería anterior y permiten una
actualización inmediata con el nuevo alcance.

Estas protecciones tienen pruebas JVM, pero no sustituyen las pruebas de permisos,
cámara y muerte real del proceso en dispositivo. El [README](README.md) distingue
la compilación instrumental de la ejecución de pruebas conectadas.
