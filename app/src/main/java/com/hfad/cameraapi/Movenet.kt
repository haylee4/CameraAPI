import android.content.Context
import org.tensorflow.lite.DataType
import org.tensorflow.lite.support.common.FileUtil
import org.tensorflow.lite.support.tensorbuffer.TensorBuffer
import java.nio.ByteBuffer

//Wrapper class for the MoveNet TFLite model
class Movenet private constructor(private val interpreter: org.tensorflow.lite.Interpreter) {

    companion object {
        private const val MODEL_NAME = "movenet.tflite"

         //Create a new instance of the Movenet wrapper
        fun newInstance(context: Context): Movenet {
            val modelFile = FileUtil.loadMappedFile(context, MODEL_NAME)
            val interpreterOptions = org.tensorflow.lite.Interpreter.Options()
            val interpreter = org.tensorflow.lite.Interpreter(modelFile, interpreterOptions)
            return Movenet(interpreter)
        }
    }


     //Process the input tensor and return the output
    fun process(inputTensor: TensorBuffer): MoveNetOutputs {
        // Create output tensor
        val outputTensor = TensorBuffer.createFixedSize(intArrayOf(1, 17, 3), DataType.FLOAT32)

        // Run the model
        interpreter.run(inputTensor.buffer, outputTensor.buffer.rewind())

        return MoveNetOutputs(outputTensor)
    }


     //Release model resources
    fun close() {
        interpreter.close()
    }


     //Wrapper class for the outputs of the MoveNet model
    class MoveNetOutputs(private val outputFeature0: TensorBuffer) {
        val outputFeature0AsTensorBuffer: TensorBuffer
            get() = outputFeature0
    }
}