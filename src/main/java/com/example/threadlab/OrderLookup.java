package com.example.threadlab;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Simulates a slow query: the method borrows a connection from HikariCP and
 * keeps it for about one second. With a pool of 5, at most 5 requests can be
 * here at the same time, however many Tomcat threads exist.
 */
@Service
class OrderLookup {

    private final JdbcTemplate jdbc;

    OrderLookup(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional // the connection is held until this method returns
    public String slowQuery() {
        jdbc.queryForObject("SELECT 1", Integer.class);
        Sleep.ms(1000);
        return "query done on " + Thread.currentThread();
    }
}
