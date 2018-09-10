import java.io.*;
import java.nio.*;

public class KiloWattHours 
{
    private String ecuId;
    private String currentDataId;
    private String voltOrRpmDataId;
    
    private int timeField;
    private int currentField;
    private int voltOrRpmField;
    private int wattField;
    public int timePowerArrayCounter;
    
    private int hourField;
    private int minField;
    private int secField;
    private int msecField;
    private int ecuField;
    private int dataIdField;
    private int byte0Field;
    private int byte1Field;
    private int byte2Field;
    private int byte3Field;
    
    private int voltOrRpmTime;
    private int goodvoltOrRpmTime;   
    private double lastvoltOrRpmTime;
    private double wattsUnderCurve;
    public double [][] timePowerArray = new double [15000][4];

    /* For the constuctor I elected to make pretty much all the array field into their own variables.
     * It certainly increased the line length but I was running into a problem where I'd change the length of the array
     * and I'd have to go and reset all the 2s to 3s.  Easier just to change one variable in the constructor, makes the arrays
     * a little easier to understand as well.
     */
    public KiloWattHours(String ecu, String dataId) 
    { 
        ecuId = ecu;
        currentDataId = "0x11";
        voltOrRpmDataId = dataId;
        
        timeField = 0;
        currentField = 1;
        voltOrRpmField = 2;
        wattField = 3;
        timePowerArrayCounter = 0;
        
        hourField = 0;
        minField = 1;
        secField = 2;
        msecField = 3;
        ecuField = 4;
        dataIdField = 5;
        byte0Field = 7;
        byte1Field = 8;
        byte2Field = 9;
        byte3Field = 10;
        
        voltOrRpmTime = 0;
        wattsUnderCurve = 0;  
        
        goodvoltOrRpmTime=0;

    }
    /*This was the first really hard part, converting the decimal numbers in the file to floats or doubles needed.
     * Truth be told I had never learned the concept of endianness, but I was able to figure it out before confirming with Jay.
     * Ended up converting string>int>byte>float>double.  Perhaps there was a simpler way, this is what I got.
     */
    public double decitofloat (String a, String b, String c, String d)
    {
        byte[] bytes = new byte[4];
        int [] ints = {Integer.parseInt(d), Integer.parseInt(c), Integer.parseInt(b), Integer.parseInt(a)};
        for(int i =0; i<4; i++)
            bytes[i] = (byte)ints[i];
        double f = ByteBuffer.wrap(bytes).getFloat();
        return f;
    }  
    /*I decided the trick to the time calculations was rather than looking at fractions of a second, just to look at miliseconds.
     * Also, looking at the total number of ms elasped since the start of the log was more helping than comparing 13:59:23.454 to 13:59:23.689
     */
    public int elaspedTimeCalc (String hours, String mins, String secs, String mili)
    {
        int tick = ((Integer.parseInt(hours) * 3600000) + (Integer.parseInt(mins) * 60000) + (Integer.parseInt(secs) * 1000) + (Integer.parseInt(mili)));
        int start = (13 * 3600000) + (59 * 60000) + (14*1000) + 260;
        int elasped = tick - start;
        return elasped;
    }
   /*Fairly simple integration method.  I used a simple right hand riemann sum.  I could have gotten crazy and tried to make the data point the center
    * of it's indivual slice, but when I tried things got weird and I figured this was good enough.  Things were a little tricky because data was 
    * not recorded at regular intervals.  Importing the current elapsed time, as well as the elasped time of the last reading did the trick.  The 
    * last time part was a little tricky.  Solution explained below.
    */
    public double riemann (double last, double next, double watts, double total)
    {
        double distance = next - last;
        double value = distance * watts;
        return total + value;
    }
    /*The money method.  Takes the raw data stream from the file and spits out the riemann sum of total power so far.  Once all the other parts worked
     * this was not super hard.  The issue I ran into here was that while the terminal had about the same number voltage and current readings, the cells 
     * had about 1/2 the voltage readings.  This meant that when integrating the "cell" data many of the individual slices were marked as havings no power since
     * they had an amp but not a volt reading.  I determined that I wanted the riemann method to use both the elasped time of the voltage being processed 
     * at the moment, as well as the last elasped time that had both an amp and volt reading.  The end result was slices where the width streched from
     * one good data point to next.  I ended up having to use 3 additional time variables because I couldn't figure out an easier way.
     */
    public double wattMs(String[] data, double wattsUnderCurve) 
    {
        if(data[ecuField].equals(ecuId)) // first we see if the data is from one of the ECUs we care about. If so, we see if it has a DataID we want
        {
            if(data[dataIdField].equals(currentDataId)) //New line in the array, see the inital elasped time, pull out the current reading
            {
                timePowerArrayCounter++;
                timePowerArray[timePowerArrayCounter][timeField] = this.elaspedTimeCalc(data[hourField],data[minField],data[secField],data[msecField]);
                timePowerArray[timePowerArrayCounter][currentField] = this.decitofloat(data[byte0Field], data[byte1Field], data[byte2Field], data[byte3Field]);
            }
            if(data[dataIdField].equals(voltOrRpmDataId)) 
            {                                              
                lastvoltOrRpmTime = goodvoltOrRpmTime; //this first time variable is actually the last processed, it's what is fed to riemann      
                voltOrRpmTime = this.elaspedTimeCalc(data[hourField],data[minField],data[secField],data[msecField]); //This is the frist, just a basic recalculation of elasped time where the volt reading is occuring

                for(int k=0;k<15000;k++) //We loop through the array and see if there is a current reading that happened at the same time
                { 
                    if(voltOrRpmTime == timePowerArray[k][timeField])
                    {
                        goodvoltOrRpmTime = voltOrRpmTime; //If there is the basic time becomes the "good" time, which will in turn become the "last" time fed to riemann after when the next volt reading is read from the file
                        timePowerArray[timePowerArrayCounter][voltOrRpmField] = this.decitofloat(data[byte0Field], data[byte1Field], data[byte2Field], data[byte3Field]);
                        if(data[ecuField].equals("3")) //simple P = VI for the battery
                               timePowerArray[timePowerArrayCounter][wattField] = (timePowerArray[timePowerArrayCounter][voltOrRpmField] * timePowerArray[timePowerArrayCounter][currentField]); //WATTS! <- I was pretty excited when I made it here and added this comment, elected to keep this.
                        if(data[ecuField].equals("2")) //the somewhat more complex calc for the engine.  I used the formula RPM * pi/30 to calculate angular momentum in randians a second.  Add in the simple tourque formula and we are good.
                               timePowerArray[timePowerArrayCounter][wattField] = ((timePowerArray[timePowerArrayCounter][voltOrRpmField] * Math.PI) / 30) * (timePowerArray[timePowerArrayCounter][currentField] * 1.1);
                        wattsUnderCurve = this.riemann(lastvoltOrRpmTime, timePowerArray[timePowerArrayCounter][timeField], timePowerArray[timePowerArrayCounter][wattField], wattsUnderCurve);
                    }     
                }
            }   
        }
        return wattsUnderCurve;
    }
}

