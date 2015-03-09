package netflix.archaius.property;

import java.lang.reflect.Constructor;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.TimeUnit;

import netflix.archaius.Config;
import netflix.archaius.ObservableProperty;
import netflix.archaius.Property;
import netflix.archaius.PropertyObserver;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Implementation of ObservableProperty which reuses the same object for each
 * type.  This implementation is assumes that each fast property is mostly accessed
 * as the same type but allows for additional types to be deserialized.  
 * Instead of incurring the overhead for caching in a hash map, the objects are 
 * stored in a CopyOnWriteArrayList and items are retrieved via a linear scan.
 * 
 * Once created a fast property cannot be removed.  However, observer may be
 * aded and removed. 
 * 
 * @author elandau
 *
 */
public class DefaultObservableProperty implements ObservableProperty {
    private final Logger LOG = LoggerFactory.getLogger(DefaultObservableProperty.class);
    
    enum Type {
        INTEGER,
        STRING,
        BYTE,
        BIG_DECIMAL,
        BIG_INTEGER,
        SHORT,
        DOUBLE,
        FLOAT,
        BOOLEAN,
        CUSTOM, // Must be last
    }

    private final String key;
    private final Config config;
    private final CopyOnWriteArrayList<AbstractProperty<?>> cache = new CopyOnWriteArrayList<AbstractProperty<?>>();
    private volatile long lastUpdateTimeInMillis = 0;
    
    public DefaultObservableProperty(String key, Config config) {
        this.key = key;
        this.config = config;
    }

    @Override
    public void update() {
        for (AbstractProperty<?> property : cache) {
            property.update();
        }
    }

    public abstract class AbstractProperty<T> implements Property<T> {
        @Override
        public int hashCode() {
            return type;
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj)
                return true;
            if (obj == null)
                return false;
            if (getClass() != obj.getClass())
                return false;
            @SuppressWarnings("rawtypes")
            AbstractProperty other = (AbstractProperty) obj;
            if (!getOuterType().equals(other.getOuterType()))
                return false;
            return (type == other.type);
        }

        private volatile T existing = null;
        private CopyOnWriteArraySet<PropertyObserver<T>> observers = new CopyOnWriteArraySet<PropertyObserver<T>>();
        private final int type;
        
        AbstractProperty(Type type) {
            this.type = type.ordinal();
        }
        
        @Override
        public void addObserver(PropertyObserver<T> observer) {
            this.observers.add(observer);
        }
        
        @Override
        public void removeObserver(PropertyObserver<T> observer) {
            this.observers.remove(observer);
        }
        
        @Override
        public void unsubscribe() {
            // Noop - for now let's keep this in the cache.
        }

        void update() {
            try {
                T next = getCurrent();
                if ((next == null || existing == null) && next == existing) {
                    return;
                }
                else if (next == null || existing == null || !existing.equals(next)) {
                    existing = next;
                    lastUpdateTimeInMillis = System.currentTimeMillis();
                    for (PropertyObserver<T> observer : observers) {
                        observer.onChange(existing);
                    }
                }
            }
            catch (Exception e) {
                LOG.warn("Unable to get current version of property '{}'. Error: {}", key, e.getMessage());
                for (PropertyObserver<T> observer : observers) {
                    observer.onError(e);
                }
            }
        }
        
        @Override
        public T get(T defaultValue) {
            T ret = existing;
            if (ret == null) 
                return defaultValue;
            return ret;
        }
        
        @Override
        public long getLastUpdateTime(TimeUnit units) {
            return units.convert(lastUpdateTimeInMillis, TimeUnit.MILLISECONDS);
        }

        private DefaultObservableProperty getOuterType() {
            return DefaultObservableProperty.this;
        }
        
        protected abstract T getCurrent() throws Exception;
    }

    /**
     * Add a new property to the end of the array list but first check
     * to see if it already exists.
     * @param newProperty
     * @return
     */
    @SuppressWarnings("unchecked")
    private <T> AbstractProperty<T> add(AbstractProperty<T> newProperty) {
        while (!cache.addIfAbsent(newProperty)) {
            for (AbstractProperty<?> property : cache) {
                if (property.type == newProperty.type) {
                    return (AbstractProperty<T>) property;
                }
            }
        }
        
        return newProperty;
    }
    
    /**
     * Retrieve a cached instance of the fast property for the specified
     * primitive data type
     * @param type
     * @return Cached type or null if does not exist
     */
    @SuppressWarnings("unchecked")
    private <T> Property<T> get(int type) {
        for (AbstractProperty<?> property : cache) {
            if (property.type == type) {
                return (AbstractProperty<T>) property;
            }
        }
        return null;
    }
    
    @Override
    public Property<String> asString() {
        Property<String> prop = get(Type.STRING.ordinal());
        if (prop == null) {
            return add(new AbstractProperty<String>(Type.STRING) {
                @Override
                protected String getCurrent() throws Exception {
                    return config.getString(key, null);
                }
            });
        }
        return prop;
    }

    @Override
    public Property<Integer> asInteger() {
        Property<Integer> prop = get(Type.INTEGER.ordinal());
        if (prop == null) {
            return add(new AbstractProperty<Integer>(Type.INTEGER) {
                @Override
                protected Integer getCurrent() throws Exception {
                    return config.getInteger(key, null);
                }
            });
        }
        return prop;
    }

    @Override
    public Property<Double> asDouble() {
        Property<Double> prop = get(Type.DOUBLE.ordinal());
        if (prop == null) {
            return add(new AbstractProperty<Double>(Type.DOUBLE) {
                @Override
                protected Double getCurrent() throws Exception {
                    return config.getDouble(key, null);
                }
            });
        }
        return prop;
    }

    @Override
    public Property<Float> asFloat() {
        Property<Float> prop = get(Type.FLOAT.ordinal());
        if (prop == null) {
            return add(new AbstractProperty<Float>(Type.FLOAT) {
                @Override
                protected Float getCurrent() throws Exception {
                    return config.getFloat(key, null);
                }
            });
        }
        return prop;
    }

    @Override
    public Property<Short> asShort() {
        Property<Short> prop = get(Type.SHORT.ordinal());
        if (prop == null) {
            return add(new AbstractProperty<Short>(Type.SHORT) {
                @Override
                protected Short getCurrent() throws Exception {
                    return config.getShort(key, null);
                }
            });
        }
        return prop;
    }

    @Override
    public Property<Byte> asByte() {
        Property<Byte> prop = get(Type.BYTE.ordinal());
        if (prop == null) {
            return add(new AbstractProperty<Byte>(Type.BYTE) {
                @Override
                protected Byte getCurrent() throws Exception {
                    return config.getByte(key, null);
                }
            });
        }
        return prop;
    }

    @Override
    public Property<BigDecimal> asBigDecimal() {
        Property<BigDecimal> prop = get(Type.BIG_DECIMAL.ordinal());
        if (prop == null) {
            return add(new AbstractProperty<BigDecimal>(Type.BIG_DECIMAL) {
                @Override
                protected BigDecimal getCurrent() throws Exception {
                    return config.getBigDecimal(key, null);
                }
            });
        }
        return prop;
    }
    
    @Override
    public Property<Boolean> asBoolean() {
        Property<Boolean> prop = get(Type.BOOLEAN.ordinal());
        if (prop == null) {
            return add(new AbstractProperty<Boolean>(Type.BOOLEAN) {
                @Override
                protected Boolean getCurrent() throws Exception {
                    return config.getBoolean(key, null);
                }
            });
        }
        return prop;
    }

    @Override
    public Property<BigInteger> asBigInteger() {
        Property<BigInteger> prop = get(Type.BIG_INTEGER.ordinal());
        if (prop == null) {
            return add(new AbstractProperty<BigInteger>(Type.BIG_INTEGER) {
                @Override
                protected BigInteger getCurrent() throws Exception {
                    return config.getBigInteger(key, null);
                }
            });
        }
        return prop;
    }

    /**
     * No caching for custom types.
     */
    @Override
    public <T> Property<T> asType(Class<T> type) {
        final Constructor<T> constructor;
        try {
            constructor = type.getConstructor(String.class);
            if (constructor != null) {
                return add(new AbstractProperty<T>(Type.CUSTOM) {
                    @Override
                    protected T getCurrent() throws Exception {
                        String value = config.getString(key);
                        if (value == null) {
                            return null;
                        }
                        else { 
                            return constructor.newInstance(value);
                        }
                    }
                });
            }
        } catch (NoSuchMethodException e) {
        } catch (SecurityException e) {
            throw new UnsupportedOperationException("No parser for type " + type.getName(), e);
        }
      
        throw new UnsupportedOperationException("No parser for type " + type.getName());
    }

}
