//
// Source: https://mkyong.com/java/java-read-a-file-from-resources-folder/
//

package com.mkyong;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.net.URL;

public class Application {

    public static void main(String[] args) throws IOException {

        Application main = new Application();
        InputStream stream = main.getFileFromResources("test.txt");

        printStream(stream);
    }

    // get file from classpath, resources folder
    private InputStream getFileFromResources(String fileName) {

        ClassLoader classLoader = getClass().getClassLoader();

        InputStream resource = classLoader.getResourceAsStream(fileName);
        if (resource == null) {
            throw new IllegalArgumentException("File not found");
        } else {
            return resource;
        }

    }

    private static void printStream(InputStream stream) throws IOException {

        if (stream == null) return;

        try (InputStreamReader reader = new InputStreamReader(stream);
             BufferedReader br = new BufferedReader(reader)) {

            String line;
            while ((line = br.readLine()) != null) {
                System.out.println(line);
            }
        }
    }

}
