package com.jesstracker.tracking

import android.graphics.Bitmap
import android.graphics.RectF
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import android.util.Log

/**
 * JerseyOcrReader — lee numeros de camiseta usando ML Kit Text Recognition.
 *
 * Estrategia:
 *   1) Cropear la zona del torso (40-75% vertical del bounding box) donde
 *      tipicamente esta el numero.
 *   2) Pasar por ML Kit OCR on-device.
 *   3) Filtrar: solo aceptar textos que sean numeros de 1-2 digitos (0-99).
 *   4) Usar votacion por mayoria: guardar los ultimos N lecturas y devolver
 *      el numero mas frecuente. Esto filtra lecturas erroneas.
 *
 * El OCR no se ejecuta cada frame — solo cuando se solicita (en seleccion
 * inicial y durante re-ID). Es rapido (~15-25ms on-device).
 */
class JerseyOcrReader {

    companion object {
        private const val TAG = "JerseyOcr"

        // Zona del torso donde esta el numero (porcentaje vertical del bounding box).
        private const val TORSO_TOP_RATIO = 0.25f
        private const val TORSO_BOTTOM_RATIO = 0.70f

        // Solo aceptar numeros de 0 a 99.
        private val JERSEY_NUMBER_REGEX = Regex("^\\d{1,2}$")

        // Cuantas lecturas guardar para votacion por mayoria.
        private const val VOTE_BUFFER_SIZE = 7

        // Minimo de votos para considerar un numero como confirmado.
        private const val MIN_VOTES_TO_CONFIRM = 3
    }

    private val recognizer: TextRecognizer = TextRecognition.getClient(
        TextRecognizerOptions.Builder().build()
    )

    // Buffer de votacion: guarda las ultimas N lecturas exitosas.
    private val voteBuffer = mutableListOf<Int>()

    /** Numero confirmado por votacion. Null si no hay suficientes lecturas consistentes. */
    var confirmedNumber: Int? = null
        private set

    /**
     * Intenta leer el numero de camiseta de un crop de jugadora.
     * Llama al callback con el numero leido (o null si no encontro).
     * Es asincrono porque ML Kit usa Tasks API.
     */
    fun readFromPatch(patch: Bitmap, onResult: (Int?) -> Unit) {
        val torsoCrop = cropTorso(patch) ?: run {
            onResult(null)
            return
        }

        val inputImage = InputImage.fromBitmap(torsoCrop, 0)

        recognizer.process(inputImage)
            .addOnSuccessListener { visionText ->
                val number = extractJerseyNumber(visionText.text)
                if (number != null) {
                    addVote(number)
                    Log.d(TAG, "Leido: #$number (confirmado: $confirmedNumber)")
                }
                onResult(number)

                if (torsoCrop !== patch) {
                    torsoCrop.recycle()
                }
            }
            .addOnFailureListener { e ->
                Log.w(TAG, "OCR fallo: ${e.message}")
                onResult(null)

                if (torsoCrop !== patch) {
                    torsoCrop.recycle()
                }
            }
    }

    /**
     * Lee sincronamente del frame completo usando un bounding box normalizado.
     * Cropea la jugadora y luego la zona del torso.
     */
    fun readFromFrame(frame: Bitmap, box: RectF, onResult: (Int?) -> Unit) {
        val frameW = frame.width.toFloat()
        val frameH = frame.height.toFloat()

        val left = (box.left * frameW).toInt().coerceIn(0, frame.width - 1)
        val top = (box.top * frameH).toInt().coerceIn(0, frame.height - 1)
        val right = (box.right * frameW).toInt().coerceIn(left + 1, frame.width)
        val bottom = (box.bottom * frameH).toInt().coerceIn(top + 1, frame.height)

        val width = (right - left).coerceAtLeast(1)
        val height = (bottom - top).coerceAtLeast(1)

        val playerCrop = Bitmap.createBitmap(frame, left, top, width, height)
        readFromPatch(playerCrop, onResult)
    }

    /** Resetear el buffer de votacion (cuando se selecciona un nuevo sujeto). */
    fun reset() {
        voteBuffer.clear()
        confirmedNumber = null
    }

    /** Verificar si un numero candidato coincide con el confirmado. */
    fun matches(candidateNumber: Int?): Boolean {
        val confirmed = confirmedNumber ?: return false
        return candidateNumber == confirmed
    }

    // --- Internos ---

    private fun cropTorso(playerPatch: Bitmap): Bitmap? {
        val torsoTop = (playerPatch.height * TORSO_TOP_RATIO).toInt()
        val torsoBottom = (playerPatch.height * TORSO_BOTTOM_RATIO).toInt()
        val torsoHeight = torsoBottom - torsoTop

        if (torsoHeight < 10 || playerPatch.width < 10) return null

        return Bitmap.createBitmap(
            playerPatch,
            0,
            torsoTop,
            playerPatch.width,
            torsoHeight
        )
    }

    private fun extractJerseyNumber(rawText: String): Int? {
        // Buscar cualquier secuencia de 1-2 digitos en el texto reconocido.
        val cleaned = rawText.trim()

        // Primero intentar match directo.
        if (cleaned.matches(JERSEY_NUMBER_REGEX)) {
            return cleaned.toIntOrNull()
        }

        // Si hay multiples lineas/palabras, buscar la primera que sea un numero valido.
        val tokens = cleaned.split(Regex("[\\s,;.]+"))
        for (token in tokens) {
            val trimmed = token.trim()
            if (trimmed.matches(JERSEY_NUMBER_REGEX)) {
                val number = trimmed.toIntOrNull()
                if (number != null && number in 0..99) {
                    return number
                }
            }
        }

        return null
    }

    private fun addVote(number: Int) {
        voteBuffer.add(number)
        if (voteBuffer.size > VOTE_BUFFER_SIZE) {
            voteBuffer.removeAt(0)
        }

        // Votacion por mayoria: el numero mas frecuente con al menos MIN_VOTES.
        val counts = voteBuffer.groupingBy { it }.eachCount()
        val best = counts.maxByOrNull { it.value }

        confirmedNumber = if (best != null && best.value >= MIN_VOTES_TO_CONFIRM) {
            best.key
        } else {
            confirmedNumber // Mantener el anterior si no hay nuevo consenso.
        }
    }
}
