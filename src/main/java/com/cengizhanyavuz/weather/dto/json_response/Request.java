package com.cengizhanyavuz.weather.dto.json_response;

public record Request(
        String type,
        String query,
        String language,
        String unit
) { }
