/*
 * Copyright 2012-2023 CodeLibs Project and the Others.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied. See the License for the specific language
 * governing permissions and limitations under the License.
 */
package org.codelibs.fess.helper;

import static org.bson.codecs.configuration.CodecRegistries.fromProviders;
import static org.bson.codecs.configuration.CodecRegistries.fromRegistries;

import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.PDFTextStripperByArea;
import org.apache.poi.hwpf.HWPFDocument;
import org.apache.poi.hwpf.extractor.WordExtractor;
import org.apache.poi.xwpf.extractor.XWPFWordExtractor;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.bson.Document;
import org.bson.codecs.configuration.CodecRegistry;
import org.bson.codecs.pojo.PojoCodecProvider;
import org.bson.types.ObjectId;

import com.mongodb.ConnectionString;
import com.mongodb.MongoClientSettings;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.gridfs.GridFSBucket;
import com.mongodb.client.gridfs.GridFSBuckets;
import com.mongodb.client.gridfs.model.GridFSUploadOptions;
import com.mongodb.client.model.InsertManyOptions;

public class PopulateDataHelper {
    private static final Logger logger = LogManager.getLogger(PopulateDataHelper.class);

    private static MongoDatabase fessMongoDB;
    private static MongoClient mongoClient;

    private static void initializeProbes() {
        try {
            if (fessMongoDB == null) {
                ConnectionString connectionString = new ConnectionString("mongodb://localhost:27017");
                CodecRegistry pojoCodecRegistry = fromProviders(PojoCodecProvider.builder().automatic(true).build());
                CodecRegistry codecRegistry = fromRegistries(MongoClientSettings.getDefaultCodecRegistry(), pojoCodecRegistry);

                MongoClientSettings clientSettings =
                        MongoClientSettings.builder().applyConnectionString(connectionString).codecRegistry(codecRegistry).build();
                mongoClient = MongoClients.create(clientSettings);
                fessMongoDB = mongoClient.getDatabase("fessCrawlerDB");
            }
        } catch (Exception e) {
            logger.error("Failed to load proprerty file {}.", e.getStackTrace());
        }
    }

    private static void populateData(Object obj) {
        initializeProbes();
        MongoCollection<Document> gradesCollection = fessMongoDB.getCollection("fessDataCollection");
        List<Document> grades = new ArrayList<>();
        try {
            grades.add(new Document("type", obj));
            gradesCollection.insertMany(grades, new InsertManyOptions().ordered(false));
        } catch (Exception e) {
            logger.error("Failed to load proprerty file {}.", e.getStackTrace());
        }
        logger.info("Populated Object  Info: {} ", obj);

    }

    public static void populateFilesIntoDB(String fileSystemPath) throws IOException {
        initializeProbes();
        GridFSBucket gridFSBucket = GridFSBuckets.create(fessMongoDB);
        try (Stream<Path> filePathStream = Files.walk(Paths.get(fileSystemPath))) {
            filePathStream.forEach(filePath -> {
                if (Files.isRegularFile(filePath)) {

                    //					System.out.println(filePath);
                    String fullFileName = filePath.toString();

                    if (fullFileName.contains(".pdf")) {
                        populatePDFFile(filePath, gridFSBucket);

                    } else if (fullFileName.contains(".doc")) {
                        populateDocFile(filePath, gridFSBucket);
                    } else {
                        populateTextFile(filePath, gridFSBucket);
                    }

                }
            });
        }

    }

    protected static void populatePDFFile(Path filePath, GridFSBucket gridFSBucket) {
        try {
            InputStream streamToUploadFrom = new FileInputStream(filePath.toString());
            PDDocument pdfDoc;
            pdfDoc = Loader.loadPDF(filePath.toFile());
            PDFTextStripperByArea stripper = new PDFTextStripperByArea();
            stripper.setSortByPosition(true);
            PDFTextStripper tStripper = new PDFTextStripper();
            String pdfFileInText = tStripper.getText(pdfDoc);
            //			System.out.println("The uploaded pdf file context: " + pdfFileInText);

            GridFSUploadOptions options = new GridFSUploadOptions().chunkSizeBytes(1024).metadata(new Document("path", pdfFileInText));

            ObjectId fileId = gridFSBucket.uploadFromStream(filePath.toString(), streamToUploadFrom, options);
            //			System.out.println("The fileId of the uploaded file is: " + fileId.toHexString());
            logger.info("Populated Object  Info: {} ", fileId.toHexString());
        } catch (FileNotFoundException e) {
            logger.error("Failed to load proprerty file {}.", e.getStackTrace());
        } catch (IOException e) {
            logger.error("Failed to load proprerty file {}.", e.getStackTrace());
        }
    }

    protected static void populateDocFile(Path filePath, GridFSBucket gridFSBucket) {
        try {

            InputStream streamToUploadFrom = new FileInputStream(filePath.toString());
            FileInputStream fis;

            if (filePath.toString().substring(filePath.toString().length() - 1).equals("x")) { //is a docx
                fis = new FileInputStream(filePath.toFile());
                XWPFDocument doc = new XWPFDocument(fis);
                XWPFWordExtractor extract = new XWPFWordExtractor(doc);
                //					System.out.println(extract.getText());
                //						 Create some custom options
                GridFSUploadOptions options =
                        new GridFSUploadOptions().chunkSizeBytes(1024).metadata(new Document("path", extract.getText()));

                ObjectId fileId = gridFSBucket.uploadFromStream(filePath.toString(), streamToUploadFrom, options);
                logger.info("Populated Object  Info: {} ", fileId.toHexString());
            } else { //is not a docx
                fis = new FileInputStream(filePath.toFile());
                HWPFDocument doc = new HWPFDocument(fis);
                WordExtractor extractor = new WordExtractor(doc);
                //					System.out.println(extractor.getText());
                //						 Create some custom options
                GridFSUploadOptions options =
                        new GridFSUploadOptions().chunkSizeBytes(1024).metadata(new Document("path", extractor.getText()));

                ObjectId fileId = gridFSBucket.uploadFromStream(filePath.toString(), streamToUploadFrom, options);
                logger.info("Populated Object  Info: {} ", fileId.toHexString());
            }

        } catch (FileNotFoundException e) {
            logger.error("Failed to load proprerty file {}.", e.getStackTrace());
        } catch (IOException e) {
            logger.error("Failed to load proprerty file {}.", e.getStackTrace());
        }
    }

    protected static void populateTextFile(Path filePath, GridFSBucket gridFSBucket) {
        try {
            InputStream streamToUploadFrom = new FileInputStream(filePath.toString());
            String content;
            content = Files.readString(filePath);
            GridFSUploadOptions options = new GridFSUploadOptions().chunkSizeBytes(1024).metadata(new Document("path", content));

            ObjectId fileId = gridFSBucket.uploadFromStream(filePath.toString(), streamToUploadFrom, options);
            logger.info("Populated Object  Info: {} ", fileId.toHexString());
        } catch (FileNotFoundException e) {
            logger.error("Failed to load proprerty file {}.", e.getStackTrace());
        } catch (IOException e) {
            logger.error("Failed to load proprerty file {}.", e.getStackTrace());
        }
    }

}
