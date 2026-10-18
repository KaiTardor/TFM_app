# Contrato de API personalizada BeSharp 1.0

Una API personalizada permite conectar BeSharp con un detector remoto sin
adaptar la aplicacion a su implementacion interna. El endpoint debe usar HTTPS y
aceptar una peticion `POST` con contenido `application/json`.

Este contrato corresponde exclusivamente a `CustomApiAnalysisProvider`. No es
el formato de OpenAI Content Provenance.
La API personalizada recibe una imagen normalizada y no dispone, a través de
esta petición, de los metadatos del original necesarios para verificar C2PA.

Se documenta el cliente existente y los requisitos que debe cumplir un servidor
compatible, no un servidor desplegado. El modelo local sigue pendiente de
entrenamiento y de definir su contrato de integración en el dispositivo.

## Configuración del destino

- Nombre visible y modelo no vacíos.
- URL HTTPS sin usuario, contraseña, query ni fragmento.
- Token Bearer opcional, guardado fuera de la URL y de los parámetros.
- Redirecciones HTTP desactivadas para no reenviar credenciales a otro destino.

La interfaz permite editar nombre, endpoint, modelo y token. Los parámetros
adicionales forman parte del contrato, pero no tienen un editor en la UI actual.

## Cabeceras

BeSharp envia siempre:

```http
Content-Type: application/json
X-BeSharp-Contract: 1.0
```

Si el usuario configura un token, tambien envia:

```http
Authorization: Bearer <token>
```

## Peticion

```json
{
  "schemaVersion": "1.0",
  "requestId": "a36c9442-66c7-40c8-aa04-b42a61cb2c47",
  "model": "detector-v1",
  "image": {
    "mimeType": "image/jpeg",
    "data": "<imagen en Base64>"
  },
  "parameters": {}
}
```

- `requestId` es el `executionId` persistente del trabajo. BeSharp reutiliza
  exactamente el mismo valor en todos los reintentos del trabajo.
- `model` contiene el identificador configurado por el usuario.
- `mimeType` conserva el tipo real de los bytes enviados.
- La entrada actual es una copia JPEG de hasta 1600 px en su dimensión mayor,
  sin metadatos EXIF. El original no se envía mediante este contrato.
- `parameters` admite como maximo 10 pares de texto. Inicialmente BeSharp no
  expone un editor de parametros en la interfaz. No admite claves asociadas a
  credenciales (`token`, `secret`, `password`, `credential` o `api_key`).

El servidor debe implementar deduplicacion idempotente por `requestId`. Si
recibe de nuevo un identificador ya procesado debe devolver el resultado
anterior y no consumir de nuevo cuota ni ejecutar otra inferencia. Dos trabajos
distintos tienen identificadores distintos, aunque analicen la misma imagen.
BeSharp admite hasta dos reintentos del Worker para fallos transitorios de la
API personalizada, conservando el mismo `requestId`. Esto incluye errores de
red, límite de peticiones e indisponibilidad temporal. Los errores de contrato
o autenticación no provocan esos reintentos.

OpenAI Content Provenance tiene su propia política de reintentos transitorios.
La integración con OpenAI es de procedencia, no de estimación visual.
La deduplicación solicitada aquí debe implementarla el servidor custom;
no debe suponerse que todos los endpoints oficiales tienen la misma garantía.

## Respuesta correcta

El servidor debe responder con HTTP `2xx` y este formato:

```json
{
  "schemaVersion": "1.0",
  "verdict": "LIKELY_SYNTHETIC",
  "syntheticProbability": 87,
  "evidenceType": "LOCAL_CLASSIFIER",
  "summary": "El detector encuentra patrones compatibles con generacion por IA.",
  "indicators": [
    "Texturas repetitivas",
    "Iluminacion inconsistente"
  ],
  "evidenceSignals": [
    {
      "signalId": "c2pa-1",
      "type": "C2PA_CREDENTIAL",
      "outcome": "DETECTED",
      "claim": "AI_GENERATION",
      "issuer": "Example issuer",
      "model": "detector-v1",
      "generatedAt": 1790000000000,
      "explanation": "El proveedor informa de una credencial C2PA.",
      "validationState": "trusted"
    }
  ]
}
```

### Valores admitidos

`verdict`:

- `LIKELY_AUTHENTIC`
- `INCONCLUSIVE`
- `LIKELY_SYNTHETIC`

`evidenceType`:

- `VISUAL_ESTIMATE`
- `C2PA_CREDENTIAL`
- `SYNTHID_WATERMARK`
- `METADATA_HINT`
- `LOCAL_CLASSIFIER`

`syntheticProbability` debe ser un entero entre `0` y `100`, o `null`. Un
resultado `INCONCLUSIVE` debe usar `null`.

`summary` es obligatorio, no puede quedar vacío y admite hasta 500 caracteres.
BeSharp conserva como máximo cinco indicadores no vacíos; cada indicador
conservado admite hasta 200 caracteres.

`evidenceSignals` es opcional y admite hasta diez señales. Los campos `type` y
`outcome` son obligatorios en cada señal. Los valores admitidos para `outcome`
son `DETECTED`, `NOT_DETECTED`, `INCONCLUSIVE` y `NOT_CHECKED`. `claim` puede ser
`AI_GENERATION`, `AI_EDITING`, `CAMERA_CAPTURE` o `UNKNOWN`. Los servidores que
solo implementan el contrato original pueden omitir esta lista.

Los tipos de señal usan los mismos valores de `EvidenceType` enumerados arriba.
`generatedAt`, si se incluye, es un timestamp en milisegundos desde Unix epoch,
no una cadena ISO-8601. El identificador de señal se limita a 100 caracteres y
los textos opcionales a 500. Conviene enviar identificadores distintos dentro
de una respuesta.

Los tipos JSON son estrictos: no se convierten números u objetos en texto.
Los indicadores conservados deben ser cadenas y los timestamps deben ser
enteros representables como `Long`. Identificadores duplicados, campos demasiado
largos y estructuras incorrectas se rechazan antes de persistir en Room.

Si la lista se omite o queda vacía y `evidenceType` es C2PA o SynthID, BeSharp
construye una señal reportada a partir del veredicto, por compatibilidad con el
contrato 1.0. Una respuesta nueva debería enviar señales explícitas para evitar
esa interpretación reducida.

### Procedencia declarada

Los valores `C2PA_CREDENTIAL` y `SYNTHID_WATERMARK` enviados por una API
personalizada son afirmaciones declaradas por ese proveedor. BeSharp no valida
localmente la firma C2PA ni detecta localmente SynthID y, por tanto, guarda estas
señales como `PROVIDER_REPORTED`. El servidor no puede elevar por si mismo una
señal a `REMOTE_VERIFIED` o `LOCALLY_VERIFIED`.

Una señal de tipo `VISUAL_ESTIMATE` se conserva con nivel visual; las otras
señales explícitas se etiquetan como reportadas. Los campos enviados por el
servidor no sustituyen la política local de resolución. `LIKELY_AUTHENTIC` es
un veredicto individual admitido por el contrato, no una prueba de captura
auténtica ni una resolución final verificada.

Aunque el servidor declare `LOCAL_CLASSIFIER`, la petición se ejecuta
remotamente. Ese tipo describe el método declarado, no la ubicación de ejecución
en BeSharp. El futuro modelo dentro del dispositivo no está integrado.

## Errores

El endpoint debe utilizar codigos HTTP convencionales:

- `400`: peticion o imagen no valida.
- `401` o `403`: token rechazado o sin permisos.
- `413`: imagen demasiado grande.
- `429`: cuota o limite de peticiones.
- `5xx`: error interno o indisponibilidad temporal.

BeSharp limita la respuesta a 256 KiB, aplica tiempos de espera y rechaza
versiones o valores que no cumplan el contrato. El fallo de este endpoint no
cancela los analisis solicitados a otros proveedores.

El cliente actual configura 30 segundos de conexión, 90 de lectura y 120 de
duración total por llamada. Los mensajes persistidos de fallo son categorías
controladas; no se almacena el cuerpo de error remoto como explicación visible.

## Identidad y privacidad

Cada destino tiene una identidad estable SHA-256 derivada de host, puerto, ruta
y modelo. Cada trabajo guarda una instantanea del endpoint HTTPS, modelo y
parametros no sensibles; nunca guarda el token. El token se obtiene del almacen
protegido por Android Keystore al ejecutar. Cambiar o borrar el destino, o
reemplazar su token aunque no cambie la URL, cancela los trabajos custom activos
asociados antes de aplicar la configuración nueva.
Si cambia la huella, el token anterior se elimina antes de publicar el destino;
dejar el token vacio nunca reutiliza el secreto del host previo.

Con el mismo destino, dejar vacío el campo de nuevo token conserva la credencial
guardada. Con un destino diferente, dejarlo vacío no hereda la clave anterior.
Cambiar la identidad del destino requiere consentimiento para el nuevo envío.

La huella incluye host, puerto, ruta y modelo, no el token ni los parámetros.
No debe utilizarse para almacenar secretos. Las respuestas quedan en Room; las
condiciones de conservación del archivo enviado dependen del servidor externo.

## Verificación y documentos relacionados

Los parsers, la identidad del destino, la política de errores y la sustitución
de tokens tienen pruebas unitarias. Esto no certifica un servidor externo ni
su implementación de deduplicación: debe probarse con su endpoint real.

- [README](README.md): proveedores, límites y estado de verificación.
- [Estructura de datos](estructura_datos.md): snapshots, resultados y señales.
- [Navegación](navegacion.md): configuración y consentimiento del usuario.
