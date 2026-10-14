# BeSharp

BeSharp es una aplicación Android que trata de detectar imágenes sinteticas
mediante evidencias. Cada evaluación agrupa los trabajos de los
proveedores seleccionados y produce una resolución final....

PD: Una señal de procedencia es evidencia sobre el origen del contenido, no una
garantía de que lo representado sea verdadero. No detectar señales tampoco
demuestra que una imagen sea auténtica.

## Funciones actuales

- Galería local con vistas de calendario y lista, y filtros de imágenes analizadas.
- Selección de imágenes por lote y captura mediante una cámara externa.
- Elección de proveedores configurados.
- Resultados y señales almacenados en Room.
- Credenciales cifradas con Android Keystore y consentimiento remoto revocable.
- Recuperación de capturas completadas y limpieza de cancelaciones explícitas.

## Proveedores y límites

| Proveedor | Entrada | Límite del original | Interpretación |
|---|---|---|---|
| OpenAI Content Provenance | Original PNG, JPEG o WebP | 50 MiB | Señales `REMOTE_VERIFIED` |
| Gemini | Original PNG, JPEG o WebP codificado en Base64 | 14 MiB | Señales `PROVIDER_REPORTED` |
| API personalizada | Copia JPEG de hasta 1600 px, sin EXIF, en Base64 | No recibe el original | Estimación visual o señales reportadas |

### OpenAI

BeSharp envía el original a `POST /v1/content_provenance_checks`. Se necesita
una API key de OpenAI Platform y acceso de la organización al endpoint.

Para imágenes, el servicio devuelve comprobaciones independientes de C2PA y
SynthID. Su cobertura se limita a señales compatibles con las herramientas de
OpenAI: no es un detector universal de imágenes generadas por otras empresas.

Referencia: [documentación de Content Provenance](https://developers.openai.com/api/docs/guides/content-provenance).

### Gemini

Por gemini api....

### API personalizada

En realización....

## Resolución y conservación

El motor aplica una política versionada con cinco resoluciones:

- `AI_GENERATED_OR_EDITED`: evidencia confirmatoria de generación o edición con IA.
- `POSSIBLY_SYNTHETIC`: señal reportada o estimación fuerte sin confirmación.
- `VERIFIED_CAPTURE`: credencial de captura confiable y verificada, sin conflicto.
- `NO_SUFFICIENT_EVIDENCE`: ausencia de indicios suficientes; no prueba autenticidad.
- `INCONCLUSIVE`: resultados insuficientes, fallos o ausencia de evidencia utilizable.

El objetivo es conservar una sola evaluación completada por imagen. Si existen
dos resultados, ambos permanecen hasta que el usuario elige cuál conservar.
Los errores parciales no ocultan evidencia válida de otros proveedores.

## Privacidad y archivos

- Cada usuario configura sus claves; no hay una clave compartida en la aplicación.
- Los originales de procedencia no se comprimen ni convierten antes del envío.
- Las copias privadas de análisis se eliminan al terminar y se limpian restos antiguos.
- OpenAI Content Provenance no es elegible para Zero Data Retention.
- Cada servicio externo aplica sus propias condiciones, conservación y cuotas.
- BeSharp no utiliza imágenes ni resultados para entrenamiento o telemetría.
- Room, credenciales y fotos privadas se excluyen del backup de Android.
- Revocar el consentimiento evita nuevos envíos autorizados y solicita cancelar
  trabajos activos; no puede retirar un archivo ya recibido por un proveedor.

Las capturas privadas usan nombres únicos y marcadores persistentes `.pending`,
`.ready` y `.cancelled`. Se recuperan las completadas cuyo registro quedó
interrumpido y se borran solo cancelaciones explícitas. Las pendientes y los
archivos antiguos sin marcador se conservan por seguridad.

En Analisis se puede recuperar o descartar una captura pendiente tras confirmar
que la camara externa esta cerrada. Si falla el registro se conserva la foto y
se ofrece reintento, incluso sin permiso para consultar MediaStore.

## Arquitectura

| Responsabilidad | Implementación |
|---|---|
| Interfaz y navegación | Jetpack Compose, Material 3 y Navigation Compose tipado |
| Estado de pantalla | ViewModels, StateFlow y observación con ciclo de vida |
| Coordinación de trabajos | Casos de uso en `application/analysis` |
| Resolución | Dominio independiente de Android, Room y HTTP |
| Datos locales | Repositorios y Room |
| Segundo plano | WorkManager |
| Red | OkHttp y Gson |
| Credenciales | Android Keystore y AES/GCM |
| Dependencias | `AppContainer` compartido por UI y Worker, sin Hilt |

Galería y Ajustes comparten la lectura de permisos de fotos. Los cambios de
alcance invalidan la sincronización anterior. Las respuestas de catálogo y
validación se aplican solo si sigue vigente su versión de configuración.

El consentimiento muestra una instantánea coherente de nombres, destinos y
tipos de entrada, y confirma que siga vigente antes de autorizar. Las escrituras
de consentimiento y las operaciones de captura se realizan fuera de Main.
La recarga de Galería reintenta registrar capturas completadas, sin borrar las
pendientes. La reconciliación no vuelve a encolar trabajo activo o finalizado.
Si WorkManager ya no conserva el registro, se cancela el trabajo local sin
reenviar una peticion ambigua. Un Worker restaurado en `RUNNING` tampoco reenvia;
solo los reintentos explicitamente devueltos a `QUEUED` pueden hacerlo.

## Pruebas esenciales

Se conservan 21 archivos JVM y 3 instrumentales centrados en resolucion de
evidencias, contratos HTTP, consentimiento, concurrencia, recuperacion de archivos
y persistencia. Se han retirado 18 archivos de pruebas de presentacion sencilla,
preferencias de apariencia y comprobaciones redundantes del esquema.

Las pruebas manuales en dispositivo han sido realizadas por el autor. No se
duplica toda esa cobertura con pruebas de componentes visuales. Se mantienen
pruebas de fallos concretos que una comprobacion manual normal no reproduce:
escrituras parciales, callbacks tardios, cancelacion y respuestas remotas invalidas.

- JVM: `gradlew.bat testDebugUnitTest`.
- Compilacion: `gradlew.bat assembleDebug assembleDebugAndroidTest lintDebug`.
- Instrumentales esenciales, cuando se quiera ejecutar los casos con dobles:
  `gradlew.bat connectedDebugAndroidTest`.

StrictMode registra acceso a disco en Main solo en debug; no afecta a release.
No se ha medido aun la duracion del arranque en frio.

## Requisitos

- Android mínimo: API 24.
- Android objetivo: API 34.
- SDK de compilación: 35.
- Entorno de compilación utilizado: JDK 17 y Android SDK Platform 35.
- Herramientas: Gradle 8.11.1, Android Gradle Plugin 8.9.2 y Navigation 2.9.0.


## Documentación

- [Estructura de datos](estructura_datos.md): persistencia, estados y archivos.
- [Navegación](navegacion.md): destinos y flujos de usuario.
- [Contrato personalizado](custom_api_contract.md): integración de detectores externos.
- [Plan de implementación](implementation_plan.md): revisión de propuestas,
  decisiones y estado de los cambios de la última auditoría.
