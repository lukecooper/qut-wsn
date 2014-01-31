package edu.qut.wsn;

/**                                            
 *
 */
public class ACI {

    /**
     *
     */
    public static double[] calculateACI(double[][] spectrogram) {
        int frameCount = spectrogram.length;
        int freqBinCount = spectrogram[0].length;
        
        double[] aciArray = new double[freqBinCount];      // array of acoustic complexity indices, one for each freq bin
        for (int j = 0; j < freqBinCount; j++)             // for all frequency bins
        {
            double deltaI = 0.0;          // set up an array to take all values in a freq bin i.e. column of matrix
            double sumI = 0.0;
            for (int r = 0; r < frameCount - 1; r++)
            {
                sumI += spectrogram[r][j];
                deltaI += Math.abs(spectrogram[r][j] - spectrogram[r + 1][j]);
            }
            if (sumI > 0.0) aciArray[j] = deltaI / sumI;      //store normalised ACI value
        }
        //DataTools.writeBarGraph(aciArray);
 
        return aciArray;    
    }
}
