package com.orange.demo.common.core.util;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.ReflectUtil;
import com.orange.demo.common.core.annotation.RelationDict;
import com.orange.demo.common.core.annotation.RelationOneToOne;
import com.orange.demo.common.core.exception.MyRuntimeException;
import com.orange.demo.common.core.object.Tuple2;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.collections4.MapUtils;
import org.apache.commons.lang3.StringUtils;
import tk.mybatis.mapper.entity.Example;

import javax.persistence.Column;
import javax.persistence.Table;
import javax.persistence.Transient;
import java.lang.reflect.Field;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 负责Model数据操作、类型转换和关系关联等行为的工具类。
 *
 * @author Orange Team
 * @date 2020-08-08
 */
@Slf4j
public class MyModelUtil {

    /**
     * 数值型字段。
     */
    public static final Integer NUMERIC_FIELD_TYPE = 0;
    /**
     * 字符型字段。
     */
    public static final Integer STRING_FIELD_TYPE = 1;
    /**
     * 日期型字段。
     */
    public static final Integer DATE_FIELD_TYPE = 2;
    /**
     * mapToColumnName和mapToColumnInfo使用的缓存。
     */
    private static Map<String, Tuple2<String, Integer>> cachedColumnInfoMap = new ConcurrentHashMap<>();

    /**
     * 拷贝源类型的集合数据到目标类型的集合中，其中源类型和目标类型中的对象字段类型完全相同。
     * NOTE: 该函数主要应用于框架中，Dto和Model之间的copy，特别针对一对一关联的深度copy。
     * 在Dto中，一对一对象可以使用Map来表示，而不需要使用从表对象的Dto。
     *
     * @param sourceCollection 源类型集合。
     * @param targetClazz      目标类型的Class对象。
     * @param <S>              源类型。
     * @param <T>              目标类型。
     * @return copy后的目标类型对象集合。
     */
    public static <S, T> List<T> copyCollectionTo(Collection<S> sourceCollection, Class<T> targetClazz) {
        List<T> targetList = new LinkedList<>();
        if (CollectionUtils.isNotEmpty(sourceCollection)) {
            for (S source : sourceCollection) {
                try {
                    T target = targetClazz.newInstance();
                    BeanUtil.copyProperties(source, target);
                    targetList.add(target);
                } catch (Exception e) {
                    log.error("Failed to call MyModelUtil.copyCollectionTo", e);
                    return Collections.emptyList();
                }
            }
        }
        return targetList;
    }

    /**
     * 拷贝源类型的对象数据到目标类型的对象中，其中源类型和目标类型中的对象字段类型完全相同。
     * NOTE: 该函数主要应用于框架中，Dto和Model之间的copy，特别针对一对一关联的深度copy。
     * 在Dto中，一对一对象可以使用Map来表示，而不需要使用从表对象的Dto。
     *
     * @param source      源类型对象。
     * @param targetClazz 目标类型的Class对象。
     * @param <S>         源类型。
     * @param <T>         目标类型。
     * @return copy后的目标类型对象。
     */
    public static <S, T> T copyTo(S source, Class<T> targetClazz) {
        if (source == null) {
            return null;
        }
        try {
            T target = targetClazz.newInstance();
            BeanUtil.copyProperties(source, target);
            return target;
        } catch (Exception e) {
            log.error("Failed to call MyModelUtil.copyTo", e);
            return null;
        }
    }

    /**
     * 映射Model对象的字段反射对象，获取与该字段对应的数据库列名称。
     *
     * @param field      字段反射对象。
     * @param modelClazz Model对象的Class类。
     * @return 该字段所对应的数据表列名称。
     */
    public static String mapToColumnName(Field field, Class<?> modelClazz) {
        return mapToColumnName(field.getName(), modelClazz);
    }

    /**
     * 映射Model对象的字段名称，获取与该字段对应的数据库列名称。
     *
     * @param fieldName  字段名称。
     * @param modelClazz Model对象的Class类。
     * @return 该字段所对应的数据表列名称。
     */
    public static String mapToColumnName(String fieldName, Class<?> modelClazz) {
        Tuple2<String, Integer> columnInfo = mapToColumnInfo(fieldName, modelClazz);
        return columnInfo == null ? null : columnInfo.getFirst();
    }

    /**
     * 映射Model对象的字段名称，获取与该字段对应的数据库列名称和字段类型。
     *
     * @param fieldName  字段名称。
     * @param modelClazz Model对象的Class类。
     * @return 该字段所对应的数据表列名称和Java字段类型。
     */
    public static Tuple2<String, Integer> mapToColumnInfo(String fieldName, Class<?> modelClazz) {
        if (StringUtils.isBlank(fieldName)) {
            return null;
        }
        StringBuilder sb = new StringBuilder(128);
        sb.append(modelClazz.getName()).append("-#-").append(fieldName);
        Tuple2<String, Integer> columnInfo = cachedColumnInfoMap.get(sb.toString());
        if (columnInfo == null) {
            Field field = ReflectUtil.getField(modelClazz, fieldName);
            if (field == null) {
                return null;
            }
            Column c = field.getAnnotation(Column.class);
            String typeName = field.getType().getSimpleName();
            String columnName = c == null ? fieldName : c.name();
            // 这里缺省情况下都是按照整型去处理，因为他覆盖太多的类型了。
            // 如Integer/Long/Double/BigDecimal，可根据实际情况完善和扩充。
            Integer type = NUMERIC_FIELD_TYPE;
            if (String.class.getSimpleName().equals(typeName)) {
                type = STRING_FIELD_TYPE;
            } else if (Date.class.getSimpleName().equals(typeName)) {
                type = DATE_FIELD_TYPE;
            }
            columnInfo = new Tuple2<>(columnName, type);
            cachedColumnInfoMap.put(sb.toString(), columnInfo);
        }
        return columnInfo;
    }

    /**
     * 映射Model主对象的Class名称，到Model所对应的表名称。
     *
     * @param modelClazz Model主对象的Class。
     * @return Model对象对应的数据表名称。
     */
    public static String mapToTableName(Class<?> modelClazz) {
        Table t = modelClazz.getAnnotation(Table.class);
        return t == null ? null : t.name();
    }

    /**
     * 根据参数中的数据列表和字段提取函数，封装stream api的方式返回指定字段的数据。
     *
     * @param dataList        数据对象列表。
     * @param fieldGetterFunc 字段获取函数。
     * @param <T>             数据对象类型。
     * @param <K>             返回字段类型。
     * @return 指定字段Set集合。
     */
    public static <T, K> Set<K> retrieveFieldSet(Collection<T> dataList, Function<T, K> fieldGetterFunc) {
        return dataList.stream().map(fieldGetterFunc).collect(Collectors.toSet());
    }

    /**
     * 在当前Service的主Model类型中，根据thisRelationField字段的RelationDict注解参数，将被关联对象thatModel中的数据，
     * 关联到thisModel对象的thisRelationField字段中。
     *
     * @param thisClazz         主对象的Class对象。
     * @param thisModel         主对象。
     * @param thatModel         字典关联对象。
     * @param thisRelationField 关联对象中保存被关联对象的字段名称。
     * @param <T>               主表对象类型。
     * @param <R>               从表对象类型。
     */
    public static <T, R> void makeDictRelation(
            Class<T> thisClazz, T thisModel, R thatModel, String thisRelationField) {
        if (thatModel == null || thisModel == null) {
            return;
        }
        // 这里不做任何空值判断，从而让配置错误在调试期间即可抛出
        Field thisTargetField = ReflectUtil.getField(thisClazz, thisRelationField);
        RelationDict r = thisTargetField.getAnnotation(RelationDict.class);
        Class<?> thatClass = r.slaveModelClass();
        Field slaveIdField = ReflectUtil.getField(thatClass, r.slaveIdField());
        Field slaveNameField = ReflectUtil.getField(thatClass, r.slaveNameField());
        Map<String, Object> m = new HashMap<>(2);
        m.put("id", ReflectUtil.getFieldValue(thatModel, slaveIdField));
        m.put("name", ReflectUtil.getFieldValue(thatModel, slaveNameField));
        ReflectUtil.setFieldValue(thisModel, thisTargetField, m);
    }

    /**
     * 在当前Service的主Model类型中，根据thisRelationField字段的RelationDict注解参数，将被关联对象集合thatModelList中的数据，
     * 逐个关联到thisModelList每一个元素的thisRelationField字段中。
     *
     * @param thisClazz         主对象的Class对象。
     * @param thisModelList     主对象列表。
     * @param thatModelList     字典关联对象列表集合。
     * @param thisRelationField 关联对象中保存被关联对象的字段名称。
     * @param <T>               主表对象类型。
     * @param <R>               从表对象类型。
     */
    public static <T, R> void makeDictRelation(
            Class<T> thisClazz, List<T> thisModelList, List<R> thatModelList, String thisRelationField) {
        if (CollectionUtils.isEmpty(thatModelList)
                || CollectionUtils.isEmpty(thisModelList)) {
            return;
        }
        // 这里不做任何空值判断，从而让配置错误在调试期间即可抛出
        Field thisTargetField = ReflectUtil.getField(thisClazz, thisRelationField);
        RelationDict r = thisTargetField.getAnnotation(RelationDict.class);
        Field masterIdField = ReflectUtil.getField(thisClazz, r.masterIdField());
        Class<?> thatClass = r.slaveModelClass();
        Field slaveIdField = ReflectUtil.getField(thatClass, r.slaveIdField());
        Field slaveNameField = ReflectUtil.getField(thatClass, r.slaveNameField());
        Map<Object, R> thatMap = new HashMap<>(20);
        thatModelList.forEach(thatModel -> {
            Object id = ReflectUtil.getFieldValue(thatModel, slaveIdField);
            thatMap.put(id, thatModel);
        });
        thisModelList.forEach(thisModel -> {
            if (thisModel != null) {
                Object id = ReflectUtil.getFieldValue(thisModel, masterIdField);
                R thatModel = thatMap.get(id);
                if (thatModel != null) {
                    Map<String, Object> m = new HashMap<>(4);
                    m.put("id", id);
                    m.put("name", ReflectUtil.getFieldValue(thatModel, slaveNameField));
                    ReflectUtil.setFieldValue(thisModel, thisTargetField, m);
                }
            }
        });
    }

    /**
     * 在当前Service的主Model类型中，根据thisRelationField字段的RelationDict注解参数，将被关联对象集合thatModelMap中的数据，
     * 逐个关联到thisModelList每一个元素的thisRelationField字段中。
     * 该函数之所以使用Map，主要出于性能优化考虑，在连续使用thatModelMap进行关联时，有效的避免了从多次从List转换到Map的过程。
     *
     * @param thisClazz         主对象的Class对象。
     * @param thisModelList     主对象列表。
     * @param thatMadelMap      字典关联对象映射集合。
     * @param thisRelationField 关联对象中保存被关联对象的字段名称。
     * @param <T>               主表对象类型。
     * @param <R>               从表对象类型。
     */
    public static <T, R> void makeDictRelation(
            Class<T> thisClazz, List<T> thisModelList, Map<Object, R> thatMadelMap, String thisRelationField) {
        if (MapUtils.isEmpty(thatMadelMap)
                || CollectionUtils.isEmpty(thisModelList)) {
            return;
        }
        // 这里不做任何空值判断，从而让配置错误在调试期间即可抛出
        Field thisTargetField = ReflectUtil.getField(thisClazz, thisRelationField);
        RelationDict r = thisTargetField.getAnnotation(RelationDict.class);
        Field masterIdField = ReflectUtil.getField(thisClazz, r.masterIdField());
        Class<?> thatClass = r.slaveModelClass();
        Field slaveNameField = ReflectUtil.getField(thatClass, r.slaveNameField());
        thisModelList.forEach(thisModel -> {
            if (thisModel != null) {
                Object id = ReflectUtil.getFieldValue(thisModel, masterIdField);
                R thatModel = thatMadelMap.get(id);
                if (thatModel != null) {
                    Map<String, Object> m = new HashMap<>(4);
                    m.put("id", id);
                    m.put("name", ReflectUtil.getFieldValue(thatModel, slaveNameField));
                    ReflectUtil.setFieldValue(thisModel, thisTargetField, m);
                }
            }
        });
    }

    /**
     * 在当前Service的主Model类型中，根据thisRelationField字段的RelationOneToOne注解参数，将被关联对象列表thatModelList中的数据，
     * 逐个关联到thisModelList每一个元素的thisRelationField字段中。
     *
     * @param thisClazz         主对象的Class对象。
     * @param thisModelList     主对象列表。
     * @param thatModelList     一对一关联对象列表。
     * @param thisRelationField 关联对象中保存被关联对象的字段名称。
     * @param <T>               主表对象类型。
     * @param <R>               从表对象类型。
     */
    public static <T, R> void makeOneToOneRelation(
            Class<T> thisClazz, List<T> thisModelList, List<R> thatModelList, String thisRelationField) {
        if (CollectionUtils.isEmpty(thatModelList)
                || CollectionUtils.isEmpty(thisModelList)) {
            return;
        }
        // 这里不做任何空值判断，从而让配置错误在调试期间即可抛出
        Field thisTargetField = ReflectUtil.getField(thisClazz, thisRelationField);
        RelationOneToOne r = thisTargetField.getAnnotation(RelationOneToOne.class);
        Field masterIdField = ReflectUtil.getField(thisClazz, r.masterIdField());
        Class<?> thatClass = r.slaveModelClass();
        Field slaveIdField = ReflectUtil.getField(thatClass, r.slaveIdField());
        Map<Object, R> thatMap = new HashMap<>(20);
        thatModelList.forEach(thatModel -> {
            Object id = ReflectUtil.getFieldValue(thatModel, slaveIdField);
            thatMap.put(id, thatModel);
        });
        // 判断放在循环的外部，提升一点儿效率。
        if (thisTargetField.getType().equals(Map.class)) {
            thisModelList.forEach(thisModel -> {
                Object id = ReflectUtil.getFieldValue(thisModel, masterIdField);
                R thatModel = thatMap.get(id);
                if (thatModel != null) {
                    ReflectUtil.setFieldValue(thisModel, thisTargetField, BeanUtil.beanToMap(thatModel));
                }
            });
        } else {
            thisModelList.forEach(thisModel -> {
                Object id = ReflectUtil.getFieldValue(thisModel, masterIdField);
                R thatModel = thatMap.get(id);
                if (thatModel != null) {
                    ReflectUtil.setFieldValue(thisModel, thisTargetField, thatModel);
                }
            });
        }
    }

    /**
     * 根据主对象和关联对象各自的关联Id函数，将主对象列表和关联对象列表中的数据关联到一起，并将关联对象
     * 设置到主对象的指定关联字段中。
     * NOTE: 用于主对象关联字段中，没有包含RelationOneToOne注解的场景。
     *
     * @param thisClazz         主对象的Class对象。
     * @param thisModelList     主对象列表。
     * @param thisIdGetterFunc  主对象Id的Getter函数。
     * @param thatModelList     关联对象列表。
     * @param thatIdGetterFunc  关联对象Id的Getter函数。
     * @param thisRelationField 主对象中保存被关联对象的字段名称。
     * @param <T>               主表对象类型。
     * @param <R>               从表对象类型。
     */
    public static <T, R> void makeOneToOneRelation(
            Class<T> thisClazz,
            List<T> thisModelList,
            Function<T, Object> thisIdGetterFunc,
            List<R> thatModelList,
            Function<R, Object> thatIdGetterFunc,
            String thisRelationField) {
        makeOneToOneRelation(thisClazz, thisModelList,
                thisIdGetterFunc, thatModelList, thatIdGetterFunc, thisRelationField, false);
    }

    /**
     * 根据主对象和关联对象各自的关联Id函数，将主对象列表和关联对象列表中的数据关联到一起，并将关联对象
     * 设置到主对象的指定关联字段中。
     * NOTE: 用于主对象关联字段中，没有包含RelationOneToOne注解的场景。
     *
     * @param thisClazz         主对象的Class对象。
     * @param thisModelList     主对象列表。
     * @param thisIdGetterFunc  主对象Id的Getter函数。
     * @param thatModelList     关联对象列表。
     * @param thatIdGetterFunc  关联对象Id的Getter函数。
     * @param thisRelationField 主对象中保存被关联对象的字段名称。
     * @param orderByThatList   如果为true，则按照ThatModelList的顺序输出。同时thisModelList被排序后的新列表替换。
     * @param <T>               主表对象类型。
     * @param <R>               从表对象类型。
     */
    public static <T, R> void makeOneToOneRelation(
            Class<T> thisClazz,
            List<T> thisModelList,
            Function<T, Object> thisIdGetterFunc,
            List<R> thatModelList,
            Function<R, Object> thatIdGetterFunc,
            String thisRelationField,
            boolean orderByThatList) {
        Field thisTargetField = ReflectUtil.getField(thisClazz, thisRelationField);
        boolean isMap = thisTargetField.getType().equals(Map.class);
        if (orderByThatList) {
            List<T> newThisModelList = new LinkedList<>();
            Map<Object, ? extends T> thisModelMap =
                    thisModelList.stream().collect(Collectors.toMap(thisIdGetterFunc, c -> c));
            thatModelList.forEach(thatModel -> {
                Object thatId = thatIdGetterFunc.apply(thatModel);
                T thisModel = thisModelMap.get(thatId);
                if (thisModel != null) {
                    ReflectUtil.setFieldValue(thisModel, thisTargetField, normalize(isMap, thatModel));
                    newThisModelList.add(thisModel);
                }
            });
            thisModelList.clear();
            thisModelList.addAll(newThisModelList);
        } else {
            Map<Object, R> thatMadelMap =
                    thatModelList.stream().collect(Collectors.toMap(thatIdGetterFunc, c -> c));
            thisModelList.forEach(thisModel -> {
                Object thisId = thisIdGetterFunc.apply(thisModel);
                R thatModel = thatMadelMap.get(thisId);
                if (thatModel != null) {
                    ReflectUtil.setFieldValue(thisModel, thisTargetField, normalize(isMap, thatModel));
                }
            });
        }
    }

    private static <M> Object normalize(boolean isMap, M model) {
        return isMap ? BeanUtil.beanToMap(model) : model;
    }

    /**
     * 转换过滤对象到与其等效的Example对象。
     *
     * @param filterModel 过滤对象。
     * @param modelClass  过滤对象的Class对象。
     * @param <T>         过滤对象类型。
     * @return 转换后的Example对象。
     */
    public static <T> Example convertFilterModelToExample(T filterModel, Class<T> modelClass) {
        if (filterModel == null) {
            return null;
        }
        Example e = new Example(modelClass);
        Example.Criteria c = e.createCriteria();
        Field[] fields = ReflectUtil.getFields(modelClass);
        for (Field field : fields) {
            if (field.getAnnotation(Transient.class) != null) {
                continue;
            }
            int modifiers = field.getModifiers();
            // transient类型的字段不能作为查询条件
            if ((modifiers & 128) == 0) {
                ReflectUtil.setAccessible(field);
                try {
                    Object o = field.get(filterModel);
                    if (o != null) {
                        c.andEqualTo(field.getName(), field.get(filterModel));
                    }
                } catch (IllegalAccessException ex) {
                    log.error("Failed to call reflection code.", ex);
                    throw new MyRuntimeException(ex);
                }
            }
        }
        return e;
    }

    /**
     * 私有构造函数，明确标识该常量类的作用。
     */
    private MyModelUtil() {
    }
}
