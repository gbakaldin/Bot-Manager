package com.vingame.bot;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class Starter {

    public static void main(String[] args) {
        SpringApplication.run(Starter.class, args);
    }

}

/*
* TODO: IMPORTANT
*  1. Fix name setting issue -- the names are never set properly
*  2. Add missing tests for non Spring managed classes
* */