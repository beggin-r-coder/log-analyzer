package com.anishka.log_analyzer.exception;

import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

@ControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public String handleFileSizeException(
            MaxUploadSizeExceededException ex,
            Model model) {

        model.addAttribute(
                "error",
                "File size exceeded the allowed limit.");

        return "error-page";
    }
}