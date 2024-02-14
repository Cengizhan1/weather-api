package com.cengizhanyavuz.weather.exception;

public record ErrorResponse (
        String success,
        Error error
) { }