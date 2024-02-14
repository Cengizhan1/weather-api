package com.cengizhanyavuz.weather.service;

import com.cengizhanyavuz.weather.constants.Constants;
import com.cengizhanyavuz.weather.dto.WeatherDto;
import com.cengizhanyavuz.weather.dto.json_response.WeatherResponse;
import com.cengizhanyavuz.weather.exception.ErrorResponse;
import com.cengizhanyavuz.weather.exception.WeatherStackApiException;
import com.cengizhanyavuz.weather.model.WeatherEntity;
import com.cengizhanyavuz.weather.repository.WeatherRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.springframework.cache.annotation.CacheConfig;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.CachePut;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Optional;

import static com.cengizhanyavuz.weather.constants.Constants.*;

@Service
@CacheConfig(cacheNames = {"weathers"})
public class WeatherService {

    private static final Logger logger = LoggerFactory.getLogger(WeatherService.class);

    private final WeatherRepository weatherRepository;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public WeatherService(WeatherRepository weatherRepository,
                          RestTemplate restTemplate) {
        this.weatherRepository = weatherRepository;
        this.restTemplate = restTemplate;

    }

    @Cacheable(key = "#city")
    public WeatherDto getWeather(String city) {

        Optional<WeatherEntity> weatherEntityOptional = weatherRepository.findFirstByRequestedCityNameOrderByUpdatedTimeDesc(city);

        return weatherEntityOptional.map(weather -> {
            if (weather.getUpdatedTime().isBefore(getLocalDateTimeNow().minusMinutes(API_CALL_LIMIT))) {
                logger.info(String.format("Creating a new city weather from weather stack api for %s due to the current one is not up-to-date", city));
                return createCityWeather(city);
            }
            logger.info(String.format("Getting weather from database for %s due to it is already up-to-date", city));
            return WeatherDto.convert(weather);
        }).orElseGet(() -> createCityWeather(city));
    }

    @CachePut(key = "#city")
    public WeatherDto createCityWeather(String city) {
        logger.info("Requesting weather stack api for city: " + city);
        String url = getWeatherStackUrl(city);
        ResponseEntity<String> responseEntity = restTemplate.getForEntity(url, String.class);
        try {
            WeatherResponse weatherResponse = objectMapper.readValue(responseEntity.getBody(), WeatherResponse.class);
            return WeatherDto.convert(saveWeatherEntity(city, weatherResponse));
        } catch (JsonProcessingException e) {
            try {
                ErrorResponse errorResponse = objectMapper.readValue(responseEntity.getBody(), ErrorResponse.class);
                throw new WeatherStackApiException(errorResponse);
            } catch (JsonProcessingException ex) {
                throw new RuntimeException(ex.getMessage());
            }
        }
    }

    @CacheEvict(allEntries = true)
    @PostConstruct
    @Scheduled(fixedRateString = "${weather-stack.cache-ttl}")
    public void clearCache() {
        logger.info("Caches are cleared");
    }

    private WeatherEntity saveWeatherEntity(String city, WeatherResponse response) {
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
        WeatherEntity weatherEntity = new WeatherEntity(city,
                response.location().name(),
                response.location().country(),
                response.current().temperature(),
                getLocalDateTimeNow(),
                LocalDateTime.parse(response.location().localtime(), formatter));

        return weatherRepository.save(weatherEntity);
    }

    private String getWeatherStackUrl(String city) {
        return WEATHER_STACK_API_BASE_URL + WEATHER_STACK_API_ACCESS_KEY_PARAM + API_KEY + WEATHER_STACK_API_QUERY_PARAM + city;
    }

    private LocalDateTime getLocalDateTimeNow() {
        Instant instant = Clock.systemDefaultZone().instant();
        return LocalDateTime.ofInstant(
                instant,
                Clock.systemDefaultZone().getZone());
    }
}
