package org.example.p2p.utils;

import java.util.Random;

public class UploadUtils {

    public static int generatedCode(){
        int DYNAMIC_STARTING_PORT = 49152;
        int DYNAMIC_ENDING_PORT = 85535;

        Random random = new Random();
        return  random.nextInt((DYNAMIC_ENDING_PORT - DYNAMIC_STARTING_PORT + 1) +DYNAMIC_STARTING_PORT);
    }
}
