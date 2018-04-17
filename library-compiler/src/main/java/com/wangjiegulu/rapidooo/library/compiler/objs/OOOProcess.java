package com.wangjiegulu.rapidooo.library.compiler.objs;

import com.google.auto.common.MoreTypes;
import com.google.common.base.Optional;

import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.FieldSpec;
import com.squareup.javapoet.JavaFile;
import com.squareup.javapoet.MethodSpec;
import com.squareup.javapoet.TypeName;
import com.squareup.javapoet.TypeSpec;
import com.wangjiegulu.rapidooo.api.OOOs;
import com.wangjiegulu.rapidooo.library.compiler.base.contract.ElementStuff;
import com.wangjiegulu.rapidooo.library.compiler.base.contract.IElementStuff;
import com.wangjiegulu.rapidooo.library.compiler.util.AnnoUtil;
import com.wangjiegulu.rapidooo.library.compiler.util.EasyType;
import com.wangjiegulu.rapidooo.library.compiler.util.ElementUtil;
import com.wangjiegulu.rapidooo.library.compiler.util.GlobalEnvironment;
import com.wangjiegulu.rapidooo.library.compiler.util.TypeNameUtil;

import java.io.Serializable;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import javax.annotation.processing.Filer;
import javax.lang.model.element.Element;
import javax.lang.model.element.Modifier;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeMirror;

/**
 * Author: wangjie
 * Email: tiantian.china.2@gmail.com
 * Date: 11/04/2018.
 */
public class OOOProcess {
    private static final SimpleDateFormat DATE_FORMAT = new SimpleDateFormat("yyyy-MM-dd HH:mm:SSS", Locale.getDefault());

    private Element generatorClassEl;
    //    private List<FromElement> fromElements = new ArrayList<>();
//    OOOs ooosAnnotation;
    private FromEntry fromEntry;

    public void setGeneratorClassEl(Element mGeneratorClassEl) {
        this.generatorClassEl = mGeneratorClassEl;
        fromEntry = new FromEntry();
        fromEntry.setGeneratorClassEl(generatorClassEl);
        fromEntry.setOoosAnno(mGeneratorClassEl.getAnnotation(OOOs.class));
        fromEntry.parse();
    }


    public void brewJava(Filer filer) throws Throwable {

        for (Map.Entry<String, FromElement> from : fromEntry.getAllFromElements().entrySet()) {
            FromElement fromElement = from.getValue();

            Element fromClassElement = fromElement.getElement();
            TypeName fromClassTypeName = ClassName.get(fromClassElement.asType());

            String fromClassName = fromClassElement.getSimpleName().toString();
            // eg. replace "BO" when generate VO
            String targetClassSimpleName = fromElement.getTargetClassSimpleName();

            TypeSpec.Builder result = TypeSpec.classBuilder(targetClassSimpleName)
                    .addModifiers(Modifier.PUBLIC)
                    .addJavadoc("From POJO: {@link $T}\n", fromClassTypeName);

            // FIXME: 12/04/2018 wangjie super class ?
            // super class
            Optional<DeclaredType> superClass = MoreTypes.nonObjectSuperclass(GlobalEnvironment.getProcessingEnv().getTypeUtils(),
                    GlobalEnvironment.getProcessingEnv().getElementUtils(), (DeclaredType) fromClassElement.asType());
            if (superClass.isPresent()) {
                result.superclass(ClassName.get(superClass.get().asElement().asType()));
            }

            // interfaces
            List<? extends TypeMirror> interfaces = MoreTypes.asTypeElement(fromClassElement.asType()).getInterfaces();
            if (null != interfaces && interfaces.size() > 0) {
                for (TypeMirror interf : interfaces) {
                    if (ElementUtil.isSameType(interf, Serializable.class)) {
                        result.addSuperinterface(ClassName.get(interf));
                    } else if (ElementUtil.isSameType(interf, ClassName.bestGuess("android.os.Parcelable"))) {
                        result.addSuperinterface(ClassName.get(interf));
                        generateParcelableElements(result, fromElement, targetClassSimpleName);
                    } else {
                        throw new RuntimeException("Not supported super interface [" + interf.toString() + "] for " + fromClassName);
                    }
                }
            }

            Map<String, FromField> allFromFields = fromElement.getAllFromFields();
            for (Map.Entry<String, FromField> item : allFromFields.entrySet()) {
                FromField fromField = item.getValue();

                Element fieldElement = fromField.getFieldOriginElement();
                String fieldName = fieldElement.getSimpleName().toString();
                FromFieldConversion fromFieldConversion = fromField.getFromFieldConversion();

                // copy all origin from fields
                if (null == fromFieldConversion || !fromFieldConversion.isReplace()) {
                    copyFromField(fromClassTypeName, result, fieldElement, fieldName);
                }

                if (null == fromFieldConversion) {
                    // getter / setter method
//                    if (MoreElements.hasModifiers(Modifier.PRIVATE).apply(fieldElement)
//                            || MoreElements.hasModifiers(Modifier.PROTECTED).apply(fieldElement)) {
                    IElementStuff realFieldElementStuff = new ElementStuff(fieldElement);
                    GetterSetterMethodNames getterSetterMethodNames = generateGetterSetterMethodName(realFieldElementStuff);
                    // add getter method
                    result.addMethod(obtainGetterMethodsBuilder(realFieldElementStuff, getterSetterMethodNames).build());
                    // add setter method
                    result.addMethod(obtainSetterMethodsBuilderDefault(realFieldElementStuff, getterSetterMethodNames).build());
//                    }
                } else {
                    // TODO: 12/04/2018 wangjie modifiers ?
                    // Generate extra Field
                    FieldSpec.Builder fieldSpecBuilder = FieldSpec.builder(fromFieldConversion.getTargetType(), fromFieldConversion.getTargetFieldName(), Modifier.PRIVATE)
                            .addJavadoc("field name conversion : {@link $T}\n",
                                    ClassName.get(generatorClassEl.asType())
                            );
                    result.addField(fieldSpecBuilder.build());


                    if (!fromFieldConversion.isReplace()) { // extra fields (not replace)
                        // getter / setter method
//                        if (MoreElements.hasModifiers(Modifier.PRIVATE).apply(fieldElement)
//                                || MoreElements.hasModifiers(Modifier.PROTECTED).apply(fieldElement)) {

                        // generate origin field getter / setter
                        IElementStuff realFieldElementStuff = new ElementStuff(fieldElement);
                        GetterSetterMethodNames getterSetterMethodNames = generateGetterSetterMethodName(realFieldElementStuff);
                        // add getter method
                        result.addMethod(obtainGetterMethodsBuilder(realFieldElementStuff, getterSetterMethodNames).build());
                        // add setter method
                        MethodSpec.Builder setterMethodBuilder = obtainConversionSetterMethodBuilder(fieldName, fromFieldConversion, realFieldElementStuff, getterSetterMethodNames);
                        result.addMethod(setterMethodBuilder.build());

                        // generate extra field getter / setter
                        realFieldElementStuff = fromField.getTargetElementStuff();
                        getterSetterMethodNames = generateGetterSetterMethodName(realFieldElementStuff);
                        // add getter method
                        result.addMethod(obtainGetterMethodsBuilder(realFieldElementStuff, getterSetterMethodNames).build());
                        // add setter method
//                            String inverseConversionMethodName = fromFieldConversion.getInverseConversionMethodName();
//                            if (!AnnoUtil.oooParamIsNotSet(inverseConversionMethodName)) { // inverseConversionMethodName has set
                        setterMethodBuilder = obtainInverseConversionSetterMethodBuilder(fromFieldConversion, realFieldElementStuff, getterSetterMethodNames);
                        result.addMethod(setterMethodBuilder.build());
//                            }

//                        }
                    } else { // replace fields
                        IElementStuff realFieldElementStuff = fromField.getTargetElementStuff();
                        GetterSetterMethodNames getterSetterMethodNames = generateGetterSetterMethodName(realFieldElementStuff);
                        // add getter method
                        result.addMethod(obtainGetterMethodsBuilder(realFieldElementStuff, getterSetterMethodNames).build());
//                        String inverseConversionMethodName = fromFieldConversion.getInverseConversionMethodName();
//                        if (!AnnoUtil.oooParamIsNotSet(inverseConversionMethodName)) { // inverseConversionMethodName has set
                        MethodSpec.Builder setterMethodBuilder = obtainInverseConversionSetterMethodBuilder(fromFieldConversion, realFieldElementStuff, getterSetterMethodNames);
                        result.addMethod(setterMethodBuilder.build());
//                        }

                    }


                }


            }

            // Constructor method
            MethodSpec.Builder defaultConstructorMethod = MethodSpec.constructorBuilder()
                    .addModifiers(Modifier.PUBLIC);
            result.addMethod(defaultConstructorMethod.build());


            String fromParamName = firstCharLower(fromClassName);

            // from to target create method
            String createTargetParam = firstCharLower(targetClassSimpleName);
            MethodSpec.Builder createMethod = MethodSpec.methodBuilder("create")
                    .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                    .returns(ClassName.get(fromElement.getTargetClassPackage(), fromElement.getTargetClassSimpleName()))
                    .addParameter(fromClassTypeName, fromParamName)
                    .addStatement(targetClassSimpleName + " " + createTargetParam + " = new " + targetClassSimpleName + "()");
            for (Map.Entry<String, FromField> item : allFromFields.entrySet()) {
                FromField fromField = item.getValue();
                Element fieldElement = fromField.getFieldOriginElement();
                GetterSetterMethodNames getterSetterMethodNames = generateGetterSetterMethodName(new ElementStuff(fieldElement));

                FromFieldConversion fromFieldConversion = fromField.getFromFieldConversion();
                String fieldElementSimpleName = fieldElement.getSimpleName().toString();

                if (null == fromFieldConversion) {
                    createMethod.addStatement(createTargetParam + "." + firstCharLower(fieldElementSimpleName) + " = " + fromParamName + "." + getterSetterMethodNames.getGetterMethodName() + "()");
                    continue;
                }

                boolean isReplace = fromFieldConversion.isReplace();
                String conversionMethodName = fromFieldConversion.getConversionMethodName();
                if (!AnnoUtil.oooParamIsNotSet(conversionMethodName)) {
                    fromFieldConversion.checkConversionMethodValidate();
                    switch (fromFieldConversion.getConversionMethodNameValidateVariableSize()) {
                        case 1:
                            if (!isReplace) {
                                createMethod.addStatement(createTargetParam + "." + fromFieldConversion.getFieldName() + " = " + fromParamName + "." + getterSetterMethodNames.getGetterMethodName() + "()");
                            }

                            createMethod.addStatement(
                                    createTargetParam + "." + fromFieldConversion.getTargetFieldName() + " = $T." + conversionMethodName + "(" + fromParamName + "." + getterSetterMethodNames.getGetterMethodName() + "())",
                                    ClassName.get(fromFieldConversion.getConversionMethodType())
                            );
                            continue;
                        case 2:
                            if (!isReplace) {
                                createMethod.addStatement(createTargetParam + "." + fromFieldConversion.getFieldName() + " = " + fromParamName + "." + getterSetterMethodNames.getGetterMethodName() + "()");
                            }
                            createMethod.addStatement(
                                    createTargetParam + "." + fromFieldConversion.getTargetFieldName() + " = $T." + conversionMethodName + "(" + createTargetParam + ", " + fromParamName + "." + getterSetterMethodNames.getGetterMethodName() + "())",
                                    ClassName.get(fromFieldConversion.getConversionMethodType())
                            );
                            continue;
                    }
                }

                if (fromFieldConversion.isTargetTypeId()) {
                    FromElement temp = fromElement.getFromEntry().getFromElementById(fromFieldConversion.getTargetTypeId());
                    createMethod.addStatement(createTargetParam + "." + fromFieldConversion.getTargetFieldName() + " = " + temp.getTargetClassSimpleName() + ".create(" + fromParamName + "." + getterSetterMethodNames.getGetterMethodName() + "())");
                    continue;
                }

                if (!isReplace) {
                    createMethod.addStatement(createTargetParam + "." + fromFieldConversion.getTargetFieldName() + " = " + fromParamName + "." + getterSetterMethodNames.getGetterMethodName() + "()");
                    continue;
                }


            }
            createMethod.addStatement("return " + createTargetParam);
            result.addMethod(createMethod.build());

            // convert to from method
            MethodSpec.Builder toFromMethod = MethodSpec.methodBuilder("to" + fromClassName)
                    .addModifiers(Modifier.PUBLIC)
                    .returns(fromClassTypeName)
                    .addStatement(fromClassName + " " + fromParamName + " = new " + fromClassName + "()");
            for (Map.Entry<String, FromField> item : allFromFields.entrySet()) {
                FromField fromField = item.getValue();
                Element fieldElement = fromField.getFieldOriginElement();
                String fromFieldName = fieldElement.getSimpleName().toString();
                GetterSetterMethodNames getterSetterMethodNames = generateGetterSetterMethodName(new ElementStuff(fieldElement));

                FromFieldConversion fromFieldConversion = fromField.getFromFieldConversion();

                if (null == fromFieldConversion) {
                    toFromMethod.addStatement(fromParamName + "." + getterSetterMethodNames.getSetterMethodName() + "(" + fromFieldName + ")");
                    continue;
                }

                boolean isReplace = fromFieldConversion.isReplace();


                String inverseConversionMethodName = fromFieldConversion.getInverseConversionMethodName();
                if (!AnnoUtil.oooParamIsNotSet(inverseConversionMethodName)) {
                    fromFieldConversion.checkInverseConversionMethodValidate();
                    switch (fromFieldConversion.getInverseConversionMethodNameValidateVariableSize()) {
                        case 1:
                            if (!isReplace) {
                                toFromMethod.addStatement(fromParamName + "." + getterSetterMethodNames.getSetterMethodName() + "(" + fromFieldName + ")");
                            } else {
                                toFromMethod.addStatement(fromParamName + "." + getterSetterMethodNames.getSetterMethodName() + "($T." + inverseConversionMethodName + "(" + fromFieldConversion.getTargetFieldName() + "))",
                                        ClassName.get(fromFieldConversion.getConversionMethodType())
                                );
                            }
                            continue;

                        case 2:
                            if (!isReplace) {
                                toFromMethod.addStatement(fromParamName + "." + getterSetterMethodNames.getSetterMethodName() + "(" + fromFieldName + ")");
                            } else {
                                toFromMethod.addStatement(fromParamName + "." + getterSetterMethodNames.getSetterMethodName() + "($T." + inverseConversionMethodName + "(this, " + fromFieldConversion.getTargetFieldName() + "))",
                                        ClassName.get(fromFieldConversion.getConversionMethodType())
                                );
                            }
                            continue;

                    }
                }


                if (fromFieldConversion.isTargetTypeId()) {
                    FromElement temp = fromElement.getFromEntry().getFromElementById(fromFieldConversion.getTargetTypeId());
                    toFromMethod.addStatement(fromParamName + "." + getterSetterMethodNames.getSetterMethodName() + "(" + fromFieldConversion.getTargetFieldName() + ".to" + temp.getElement().getSimpleName().toString() + "())");
                    continue;
                }

                if (!isReplace) {
                    toFromMethod.addStatement(fromParamName + "." + getterSetterMethodNames.getSetterMethodName() + "(" + fromFieldName + ")");
                } else {
                    toFromMethod.addComment("Loss field: " + fromFieldName + ", recommend to use `inverseConversionMethodName`.");
                }


            }
            toFromMethod.addStatement("return " + fromParamName);
            result.addMethod(toFromMethod.build());

            // TODO: 11/04/2018 wangjie, need copy methods here from `from pojo`?

            JavaFile.builder(fromElement.getTargetClassPackage(), result.build())
                    .addFileComment("GENERATED CODE BY RapidOOO. DO NOT MODIFY! $S, POJOGenerator: $S",
                            DATE_FORMAT.format(new Date(System.currentTimeMillis())),
                            generatorClassEl.asType().toString())
                    .skipJavaLangImports(true)
                    .build()
                    .writeTo(filer);

        }

    }

    private MethodSpec.Builder obtainInverseConversionSetterMethodBuilder(FromFieldConversion fromFieldConversion, IElementStuff realFieldElementStuff, GetterSetterMethodNames getterSetterMethodNames) {
        String inverseConversionMethodName = fromFieldConversion.getInverseConversionMethodName();
        // add setter method
        // inverse
        MethodSpec.Builder setterMethodBuilder = obtainSetterMethodsBuilderDefault(realFieldElementStuff, getterSetterMethodNames);
        if (!fromFieldConversion.isReplace() && !AnnoUtil.oooParamIsNotSet(inverseConversionMethodName)) { // inverseConversionMethodName has set
            fromFieldConversion.checkInverseConversionMethodValidate();
            int conversionMethodValidateVariableSize = fromFieldConversion.getInverseConversionMethodNameValidateVariableSize();
            switch (conversionMethodValidateVariableSize) {
                case 1:
                    setterMethodBuilder.addStatement(
                            "this." + fromFieldConversion.getFieldName() + " = $T." + inverseConversionMethodName + "(" + realFieldElementStuff.getSimpleName() + ")",
                            ClassName.get(fromFieldConversion.getConversionMethodType())
                    );
                    break;
                case 2:
                    setterMethodBuilder.addStatement(
                            "this." + fromFieldConversion.getFieldName() + " = $T." + inverseConversionMethodName + "(this, " + realFieldElementStuff.getSimpleName() + ")",
                            ClassName.get(fromFieldConversion.getConversionMethodType())
                    );
                    break;
                default:
                    throw new RuntimeException("Invalidate method: " + inverseConversionMethodName);
            }
        }

        return setterMethodBuilder;
    }

    private MethodSpec.Builder obtainConversionSetterMethodBuilder(String fieldName, FromFieldConversion fromFieldConversion, IElementStuff realFieldElementStuff, GetterSetterMethodNames getterSetterMethodNames) {
        MethodSpec.Builder setterMethodBuilder = obtainSetterMethodsBuilderDefault(realFieldElementStuff, getterSetterMethodNames);

        String conversionMethodName = fromFieldConversion.getConversionMethodName();
        if (!AnnoUtil.oooParamIsNotSet(conversionMethodName)) {
            fromFieldConversion.checkConversionMethodValidate();
            int conversionMethodValidateVariableSize = fromFieldConversion.getConversionMethodNameValidateVariableSize();

            switch (conversionMethodValidateVariableSize) {
                case 1:
                    setterMethodBuilder.addStatement(
                            "this." + fromFieldConversion.getTargetFieldName() + " = $T." + conversionMethodName + "(" + fieldName + ")",
                            ClassName.get(fromFieldConversion.getConversionMethodType())
                    );
                    break;
                case 2:
                    setterMethodBuilder.addStatement(
                            "this." + fromFieldConversion.getTargetFieldName() + " = $T." + conversionMethodName + "(this, " + fieldName + ")",
                            ClassName.get(fromFieldConversion.getConversionMethodType())
                    );
                    break;
                default:
                    throw new RuntimeException("Invalidate method: " + conversionMethodName);
            }
        }
        return setterMethodBuilder;
    }

    private void copyFromField(TypeName fromClassTypeName, TypeSpec.Builder result, Element fieldElement, String fieldName) {
        FieldSpec.Builder fieldSpecBuilder = FieldSpec.builder(ClassName.get(fieldElement.asType()), fieldName, getModifiersArray(fieldElement))
                .addJavadoc("field name: {@link $T#$L}\n",
                        fromClassTypeName,
                        fieldElement.getSimpleName().toString()
                );
        result.addField(fieldSpecBuilder.build());
    }


    private MethodSpec.Builder obtainGetterMethodsBuilder(IElementStuff fieldElement, GetterSetterMethodNames getterSetterMethodNames) {
        String fieldName = fieldElement.getSimpleName();

        return MethodSpec.methodBuilder(getterSetterMethodNames.getGetterMethodName())
                .addModifiers(Modifier.PUBLIC)
                .returns(fieldElement.asType())
                .addStatement("return " + fieldName);
    }

    private MethodSpec.Builder obtainSetterMethodsBuilderDefault(IElementStuff fieldElement, GetterSetterMethodNames getterSetterMethodNames) {
        String fieldName = fieldElement.getSimpleName();

        return MethodSpec.methodBuilder(getterSetterMethodNames.getSetterMethodName())
                .addModifiers(Modifier.PUBLIC)
                .addParameter(fieldElement.asType(), fieldName)
                .returns(void.class)
                .addStatement("this." + fieldName + " = " + fieldName);
    }


    private GetterSetterMethodNames generateGetterSetterMethodName(IElementStuff fieldElement) {
        GetterSetterMethodNames getterSetterMethodNames = new GetterSetterMethodNames();

        String fieldName = fieldElement.getSimpleName();
        String firstCharUpperFieldName = firstCharUpper(fieldName);

        if (fieldElement.asType() == TypeName.BOOLEAN) {
            if ("is".equalsIgnoreCase(fieldName.substring(0, 2))) {
                getterSetterMethodNames.setGetterMethodName(fieldName);
                getterSetterMethodNames.setSetterMethodName("set" + firstCharUpper(fieldName.substring(2)));
            } else {
                getterSetterMethodNames.setGetterMethodName("is" + firstCharUpperFieldName);
                getterSetterMethodNames.setSetterMethodName("set" + firstCharUpperFieldName);
            }
        } else if (Boolean.class.getCanonicalName().equals(fieldElement.asType().toString())) {
            if ("is".equalsIgnoreCase(fieldName.substring(0, 2))) {
                getterSetterMethodNames.setGetterMethodName("get" + fieldName.substring(2));
                getterSetterMethodNames.setSetterMethodName("set" + firstCharUpper(fieldName.substring(2)));
            } else {
                getterSetterMethodNames.setGetterMethodName("get" + firstCharUpperFieldName);
                getterSetterMethodNames.setSetterMethodName("set" + firstCharUpperFieldName);
            }
        } else {
            getterSetterMethodNames.setGetterMethodName("get" + firstCharUpperFieldName);
            getterSetterMethodNames.setSetterMethodName("set" + firstCharUpperFieldName);
        }
        return getterSetterMethodNames;
    }

    private void generateParcelableElements(TypeSpec.Builder result, FromElement fromElement, String targetClassSimpleName) {
        ClassName parcelClassName = ClassName.bestGuess("android.os.Parcel");
        MethodSpec.Builder parcelConstructorMethodBuilder = MethodSpec.constructorBuilder()
                .addModifiers(Modifier.PROTECTED)
                .addParameter(parcelClassName, "parcel");

        FieldSpec.Builder fieldSpec = FieldSpec.builder(EasyType.bestGuessDeep2("android.os.Parcelable.Creator<" + targetClassSimpleName + ">"), "CREATOR", Modifier.STATIC, Modifier.PUBLIC)
                .initializer("new Parcelable.Creator<" + targetClassSimpleName + ">() {\n" +
                        "        @Override\n" +
                        "        public " + targetClassSimpleName + " createFromParcel($T source) {\n" +
                        "            return new " + targetClassSimpleName + "(source);\n" +
                        "        }\n" +
                        "\n" +
                        "        @Override\n" +
                        "        public " + targetClassSimpleName + "[] newArray(int size) {\n" +
                        "            return new " + targetClassSimpleName + "[size];\n" +
                        "        }\n" +
                        "    }", parcelClassName);
        result.addField(fieldSpec.build());

        // describeContents()
        MethodSpec.Builder describeContentsMethod = MethodSpec.methodBuilder("describeContents")
                .returns(int.class)
                .addAnnotation(Override.class)
                .addModifiers(Modifier.PUBLIC)
                .addStatement("return 0");

        result.addMethod(describeContentsMethod.build());


        MethodSpec.Builder writeToParcelMethod = MethodSpec.methodBuilder("writeToParcel")
                .returns(void.class)
                .addAnnotation(Override.class)
                .addParameter(parcelClassName, "dest")
                .addParameter(int.class, "flags")
                .addModifiers(Modifier.PUBLIC);

        Map<String, FromField> allFromFields = fromElement.getAllFromFields();
        for (Map.Entry<String, FromField> item : allFromFields.entrySet()) {
            FromField fromField = item.getValue();

            Element fieldElement = fromField.getFieldOriginElement();
            String fieldName = fieldElement.getSimpleName().toString();
            FromFieldConversion fromFieldConversion = fromField.getFromFieldConversion();

            if (null == fromFieldConversion) {
                IElementStuff elementStuff = new ElementStuff(fieldElement);
                addParcelableReadStatement(parcelConstructorMethodBuilder, elementStuff);
                addParcelableWriteStatement(writeToParcelMethod, elementStuff);
            } else {
                if (!fromFieldConversion.isReplace()) {
                    IElementStuff elementStuff = new ElementStuff(fieldElement);
                    addParcelableReadStatement(parcelConstructorMethodBuilder, elementStuff);
                    addParcelableWriteStatement(writeToParcelMethod, elementStuff);
                }

                IElementStuff elementStuff = fromField.getTargetElementStuff();
                addParcelableReadStatement(parcelConstructorMethodBuilder, elementStuff);
                addParcelableWriteStatement(writeToParcelMethod, elementStuff);
            }

        }

        result.addMethod(parcelConstructorMethodBuilder.build());
        result.addMethod(writeToParcelMethod.build());
    }

    private void addParcelableReadStatement(MethodSpec.Builder parcelConstructorMethodBuilder, IElementStuff fieldElementStuff) {
        TypeName typeName = fieldElementStuff.asType();
        String fieldName = fieldElementStuff.getSimpleName();
        if (typeName.isPrimitive()) {
            parcelConstructorMethodBuilder.addStatement("this." + fieldName + " = " + checkNullCondition(TypeNameUtil.getParcelablePrimitiveReadStatement(typeName),
                    "failed parcelable field: " + typeName + " " + fieldName
            ));
        } else if (typeName.isBoxedPrimitive()) {
            parcelConstructorMethodBuilder.addStatement("this." + fieldName + " = "
                    + checkNullCondition(TypeNameUtil.getParcelableBoxPrimitiveReadStatement(typeName),
                    "failed parcelable field: " + typeName + " " + fieldName
            ));
        } else {
            parcelConstructorMethodBuilder.addStatement("this." + fieldName + " = " + checkNullCondition(TypeNameUtil.getParcelableOtherReadStatement(typeName),
                    "failed parcelable field: " + typeName + " " + fieldName
            ));
        }
    }

    private void addParcelableWriteStatement(MethodSpec.Builder parcelConstructorMethodBuilder, IElementStuff fieldElementStuff) {
        TypeName typeName = fieldElementStuff.asType();
        String fieldName = fieldElementStuff.getSimpleName();
        if (typeName.isPrimitive()) {
            parcelConstructorMethodBuilder.addStatement(checkNullCondition(TypeNameUtil.getParcelablePrimitiveWriteStatement(typeName, fieldName),
                    "failed parcelable field: " + typeName + " " + fieldName
            ));
        } else if (typeName.isBoxedPrimitive()) {
            parcelConstructorMethodBuilder.addStatement("dest.writeValue(this." + fieldName + ")");
        } else {
            parcelConstructorMethodBuilder.addStatement(checkNullCondition(TypeNameUtil.getParcelableOtherWriteStatement(typeName, fieldName),
                    "failed parcelable field: " + typeName + " " + fieldName
            ));
        }
    }

    private String firstCharUpper(String fieldName) {
        return fieldName.substring(0, 1).toUpperCase() + fieldName.substring(1);
    }

    private String firstCharLower(String fieldName) {
        return fieldName.substring(0, 1).toLowerCase() + fieldName.substring(1);
    }

    private Modifier[] getModifiersArray(Element e) {
        Set<Modifier> modifierSet = e.getModifiers();
        Iterator<Modifier> iter = modifierSet.iterator();
        Modifier[] modifiers = new Modifier[modifierSet.size()];
        for (int i = 0; i < modifiers.length; i++) {
            modifiers[i] = iter.next();
        }
        return modifiers;
    }

    private static <T> T checkNullCondition(T t, String s) {
        if (null == t) {
            throw new RuntimeException(s);
        }
        return t;
    }

    @Override
    public String toString() {
        return "OOOProcess{" +
                "generatorClassEl=" + generatorClassEl +
                ", fromEntry=" + fromEntry +
                '}';
    }
}
