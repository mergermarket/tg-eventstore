package com.timgroup.eventstore.mysql;

import com.mchange.v2.c3p0.ComboPooledDataSource;
import com.mchange.v2.c3p0.PooledDataSource;
import com.typesafe.config.Config;

import java.util.Properties;

import static java.lang.String.format;

public final class StacksConfiguredDataSource {

    public static final int DEFAULT_MAX_POOLSIZE = 15;

    private StacksConfiguredDataSource() { /* prevent instantiation */ }

    public static PooledDataSource pooledMasterDb(Properties properties, String configPrefix) {
        return pooledMasterDb(properties, configPrefix, DEFAULT_MAX_POOLSIZE);
    }

    public static PooledDataSource pooledMasterDb(Properties properties, String configPrefix, int maxPoolSize) {
        return getPooledDataSource(properties, configPrefix, "hostname", maxPoolSize);
    }

    public static PooledDataSource pooledReadOnlyDb(Properties properties, String configPrefix) {
        return pooledReadOnlyDb(properties, configPrefix, DEFAULT_MAX_POOLSIZE);
    }

    public static PooledDataSource pooledReadOnlyDb(Properties properties, String configPrefix, int maxPoolSize) {
        return getPooledDataSource(properties, configPrefix, "read_only_cluster", maxPoolSize);
    }

    private static PooledDataSource getPooledDataSource(Properties properties, String configPrefix, String host_propertyname, int maxPoolSize) {
        String prefix = configPrefix;

        if (properties.getProperty(prefix + host_propertyname) == null) {
            prefix = "db." + prefix + ".";
            if (properties.getProperty(prefix) == null) {
                throw new IllegalArgumentException("No " + configPrefix + host_propertyname + " property available to configure data source");
            }
        }

        return pooled(
                properties.getProperty(prefix + host_propertyname),
                Integer.parseInt(properties.getProperty(prefix + "port")),
                properties.getProperty(prefix + "username"),
                properties.getProperty(prefix + "password"),
                properties.getProperty(prefix + "database"),
                properties.getProperty(prefix + "driver"),
                maxPoolSize
        );
    }


    public static PooledDataSource pooledMasterDb(Config config) {
        return pooledMasterDb(config, DEFAULT_MAX_POOLSIZE);
    }

    public static PooledDataSource pooledMasterDb(Config config, int maxPoolSize) {
        return pooled(
                config.getString("hostname"),
                config.getInt("port"),
                config.getString("username"),
                config.getString("password"),
                config.getString("database"),
                config.getString("driver"),
                maxPoolSize
        );
    }

    public static PooledDataSource pooledReadOnlyDb(Config config) {
        return pooledReadOnlyDb(config, DEFAULT_MAX_POOLSIZE);
    }

    public static PooledDataSource pooledReadOnlyDb(Config config, int maxPoolSize) {
        return pooled(
                config.getString("read_only_cluster"),
                config.getInt("port"),
                config.getString("username"),
                config.getString("password"),
                config.getString("database"),
                config.getString("driver"),
                maxPoolSize
        );
    }

    private static PooledDataSource pooled(String hostname, int port, String username, String password, String database, String driver, int maxPoolsize) {
        ComboPooledDataSource dataSource = new ComboPooledDataSource();
        dataSource.setJdbcUrl(format("jdbc:mysql://%s:%d/%s?rewriteBatchedStatements=true",
                hostname,
                port,
                database));
        dataSource.setUser(username);
        dataSource.setPassword(password);
        dataSource.setIdleConnectionTestPeriod(60 * 5);
        dataSource.setMinPoolSize(3);
        dataSource.setInitialPoolSize(3);
        dataSource.setAcquireIncrement(1);
        dataSource.setMaxPoolSize(maxPoolsize);

        try {
            Class.forName(driver);
        } catch (ClassNotFoundException e) {
            throw new RuntimeException(e);
        }
        return dataSource;
    }
}
