module com.mediaconsumer {
    requires javafx.controls;
    requires javafx.fxml;
    requires org.slf4j;
    requires javafx.media;


    opens com.mediaconsumer to javafx.fxml;
    exports com.mediaconsumer;
    exports com.mediaconsumer.controller;
    opens com.mediaconsumer.controller to javafx.fxml;
}