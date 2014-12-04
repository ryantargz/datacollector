/**
 * (c) 2014 StreamSets, Inc. All rights reserved. May not
 * be copied, modified, or distributed in whole or part without
 * written consent of StreamSets, Inc.
 */
package com.streamsets.pipeline.sdk.annotationsprocessor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.streamsets.pipeline.api.ConfigDef;
import com.streamsets.pipeline.api.FieldSelector;
import com.streamsets.pipeline.api.FieldValueChooser;
import com.streamsets.pipeline.api.GenerateResourceBundle;
import com.streamsets.pipeline.api.RawSource;
import com.streamsets.pipeline.api.StageDef;
import com.streamsets.pipeline.api.ValueChooser;
import com.streamsets.pipeline.config.ChooserMode;
import com.streamsets.pipeline.config.ConfigDefinition;
import com.streamsets.pipeline.config.ModelDefinition;
import com.streamsets.pipeline.config.ModelType;
import com.streamsets.pipeline.config.RawSourceDefinition;
import com.streamsets.pipeline.config.StageDefinition;
import com.streamsets.pipeline.config.StageType;
import com.streamsets.pipeline.container.Utils;
import com.streamsets.pipeline.sdk.util.StageHelper;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.annotation.processing.SupportedSourceVersion;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.PackageElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.MirroredTypeException;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.ElementFilter;
import javax.tools.Diagnostic;
import javax.tools.FileObject;
import javax.tools.StandardLocation;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;


@SupportedAnnotationTypes({"com.streamsets.pipeline.api.StageDef",
  "com.streamsets.pipeline.api.GenerateResourceBundle"})
@SupportedSourceVersion(SourceVersion.RELEASE_7)
public class PipelineAnnotationsProcessor extends AbstractProcessor {

  /**************** constants ********************/

  private static final String SOURCE = "SOURCE";
  private static final String PROCESSOR = "PROCESSOR";
  private static final String TARGET = "TARGET";
  private static final String ERROR = "ERROR";
  private static final String BUNDLE_SUFFIX = ".properties";
  private static final String STAGE_LABEL = "label";
  private static final String STAGE_DESCRIPTION = "description";
  private static final String SEPARATOR = ".";
  private static final String LABEL = "label";
  private static final String DESCRIPTION = "description";
  private static final String EQUALS = "=";
  private static final String DOT = ".";
  private static final String UNDER_SCORE = "_";
  private static final String DEFAULT_CONSTRUCTOR = "<init>";
  private static final String PIPELINE_STAGES_JSON = "PipelineStages.json";

  /**************** private variables ************/

  /*Map that keeps track of all the encountered stage implementations and the versions*/
  private Map<String, String> stageNameToVersionMap = null;
  /*An instance of StageCollection collects all the stage definitions and configurations
  in maps and will later be serialized into json.*/
  private List<StageDefinition> stageDefinitions = null;
  /*Indicates if there is an error while processing stages*/
  private boolean stageDefValidationError = false;
  /*Indicates if there is an error while processing stage error definition enum*/
  private boolean errorEnumValidationFailure = false;
  /*Map of enum names that need resource bundles to names of resource bundles*/
  private Map<String, String> enumsNeedingResourceBundles;
  /*literal vs value maps for the stage error def enum*/
  private Map<String, Map<String, String>> errorEnumToLiteralsMap;
  /*Json object mapper to generate json file for the stages*/
  private final ObjectMapper json;
  /*Set of stage names for which resource bundles must be generated*/
  private final Set<String> stagesNeedingResourceBundles;


  /***********************************************/
  /**************** public API *******************/
  /***********************************************/

  public PipelineAnnotationsProcessor() {
    super();
    stageDefinitions = new ArrayList<>();
    stageNameToVersionMap = new HashMap<>();
    errorEnumToLiteralsMap = new HashMap<>();
    json = new ObjectMapper();
    json.enable(SerializationFeature.INDENT_OUTPUT);
    stagesNeedingResourceBundles = new HashSet<>();
    enumsNeedingResourceBundles = new HashMap<>();
  }

  @Override
  public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {

    //process classes annotated with StageDef annotation
    //Also note down stages which need resource bundle generation
    for(Element e : roundEnv.getElementsAnnotatedWith(StageDef.class)) {
      ElementKind eKind = e.getKind();
      //It will most likely be a class. being extra safe
      if(eKind.isClass()) {
        stageDefinitions.add(createStageConfig((TypeElement)e));
      }
    }

    //Find all elements that need resource bundle generation and separate out the enums implementing ErrorId interface
    //validate such enums and mark them for resource bundle generation
    for(Element e : roundEnv.getElementsAnnotatedWith(GenerateResourceBundle.class)) {
      ElementKind eKind = e.getKind();
      if(eKind.isClass()) {
        TypeElement typeElement = (TypeElement) e;
        String elementName = StageHelper.getStageNameFromClassName(typeElement.getQualifiedName().toString());
        if(stageNameToVersionMap.containsKey(elementName)) {
          //these are stages needing resource bundles.
          stagesNeedingResourceBundles.add(elementName);
        } else if(validateErrorDefinition(typeElement)) {
          //As of now these have to be enums that implement ErrorId. Validate and note down enums needing resource
          //bundle generation
          createErrorEnum(typeElement);
          enumsNeedingResourceBundles.put(elementName, getClassNameFromTypeMirror(typeElement.asType()));
        } else {
          //error scenario - neither a stage nor enum but has GenerateResourceBundle annotation on it
          printError("validation.not.a.stage.or.enum",
              "Class {} is neither a stage implementation nor an ErrorId implementation but is annotated with " +
                  "'GenerateResourceBundle' annotation. This annotation is supported only on stage or ErrorId " +
                  "implementations", typeElement.getQualifiedName());
        }
      }
    }

    //generate stuff only in the last round.
    // Last round is meant for cleanup and stuff but it is ok to generate
    // because they are not source files.
    if(roundEnv.processingOver()) {
      //generate a json file containing all the stage definitions
      //generate a -bundle.properties file containing the labels and descriptions of
      // configuration options
      if(!stageDefValidationError) {
        generateConfigFile();
        generateStageBundles();
      }
      //generate a error bundle
      if(!errorEnumValidationFailure &&
          !errorEnumToLiteralsMap.isEmpty()) {
        generateErrorBundle();
      }
    }
    return true;
  }

  /**************************************************************************/
  /********************* Private helper methods *****************************/
  /**************************************************************************/


  /**
   * Generates <className>.properties file for each stage definition.
   */
  private void generateStageBundles() {
    //get source location
    for(StageDefinition s : stageDefinitions) {
      if(stagesNeedingResourceBundles.contains(s.getName())) {
        try {
          FileObject resource = processingEnv.getFiler().createResource(StandardLocation.CLASS_OUTPUT,
              s.getClassName().substring(0, s.getClassName().lastIndexOf(DOT)),
              s.getClassName().substring(s.getClassName().lastIndexOf(DOT) + 1) + BUNDLE_SUFFIX, (Element[]) null);
          PrintWriter pw = new PrintWriter(resource.openWriter());
          pw.println(STAGE_LABEL + EQUALS + s.getLabel());
          pw.println(STAGE_DESCRIPTION + EQUALS + s.getDescription());
          for (ConfigDefinition c : s.getConfigDefinitions()) {
            pw.println(c.getName() + SEPARATOR + LABEL + EQUALS + c.getLabel());
            pw.println(c.getName() + SEPARATOR + DESCRIPTION + EQUALS + c.getDescription());
          }
          pw.flush();
          pw.close();
        } catch (IOException e) {
          processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR, e.getMessage());
        }
      }
    }
  }

  /**
   * Generates the "PipelineStages.json" file with the configuration options
   */
  private void generateConfigFile() {
    try {
      FileObject resource = processingEnv.getFiler().createResource(StandardLocation.CLASS_OUTPUT, "",
        PIPELINE_STAGES_JSON);
      json.writeValue(resource.openOutputStream(), stageDefinitions);
    } catch (IOException e) {
      processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR, e.getMessage());
    }
  }

  /**
   * Generates <enumName>.properties file.
   */
  private void generateErrorBundle() {
    for(Map.Entry<String, String> e : enumsNeedingResourceBundles.entrySet()) {
      String enumClassName = e.getValue();
      try {
        FileObject resource = processingEnv.getFiler().createResource(StandardLocation.CLASS_OUTPUT,
            enumClassName.substring(0, enumClassName.lastIndexOf(DOT)),
            enumClassName.substring(enumClassName.lastIndexOf(DOT) + 1,
                enumClassName.length())
                + BUNDLE_SUFFIX, (Element[]) null);
        PrintWriter pw = new PrintWriter(resource.openWriter());
        for (Map.Entry<String, String> entry : errorEnumToLiteralsMap.get(e.getKey()).entrySet()) {
          pw.println(entry.getKey() + EQUALS + entry.getValue());
        }
        pw.flush();
        pw.close();
      } catch (IOException ex) {
        processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR, ex.getMessage());
      }
    }
  }

  /**
   * Creates and populates an instance of StageConfiguration from the Stage definition
   * @param typeElement The type element on which the stage annotation is present
   * @return returns a StageDefinition object
   */
  private StageDefinition createStageConfig(TypeElement typeElement) {
    StageDef stageDefAnnotation = typeElement.getAnnotation(StageDef.class);
    //Process all fields with ConfigDef annotation
    List< ConfigDefinition> configDefinitions = getConfigDefsFromTypeElement(typeElement);

    StageDefinition stageDefinition = null;
    if(validateStageDef(typeElement, stageDefAnnotation)) {
      RawSource rawSourceAnnot = typeElement.getAnnotation(RawSource.class);
      RawSourceDefinition rawSourceDefinition = null;
      if(rawSourceAnnot != null) {
        rawSourceDefinition = getRawSourceDefinition(rawSourceAnnot);
      }

      String stageName = StageHelper.getStageNameFromClassName(typeElement.getQualifiedName().toString());
      stageDefinition = new StageDefinition(
          typeElement.getQualifiedName().toString(),
          stageName,
          stageDefAnnotation.version(),
          stageDefAnnotation.label(),
          stageDefAnnotation.description(),
          StageType.valueOf(getStageTypeFromElement(typeElement)),
          configDefinitions,
          stageDefAnnotation.onError(),
          rawSourceDefinition,
          stageDefAnnotation.icon());
    } else {
      stageDefValidationError = true;
    }
    return stageDefinition;
  }

  private void createErrorEnum(TypeElement typeElement) {
    String enumName = StageHelper.getStageNameFromClassName(typeElement.getQualifiedName().toString());
    List<? extends Element> enclosedElements = typeElement.getEnclosedElements();
    List<VariableElement> variableElements = ElementFilter.fieldsIn(enclosedElements);
    Map<String, String> literalToValueMap = new HashMap<>();
    for (VariableElement variableElement : variableElements) {
      if(variableElement.getKind() == ElementKind.ENUM_CONSTANT) {
        literalToValueMap.put(variableElement.getSimpleName().toString(),
            (String) variableElement.getConstantValue());
      }
    }
    errorEnumToLiteralsMap.put(enumName, literalToValueMap);
  }

  private List<ConfigDefinition> getConfigDefsFromTypeElement(TypeElement typeElement) {
    List<ConfigDefinition> configDefinitions = new ArrayList<>();
    List<VariableElement> variableElements = getAllFields(typeElement);
    for (VariableElement variableElement : variableElements) {
      ConfigDef configDefAnnot = variableElement.getAnnotation(ConfigDef.class);
      if(configDefAnnot != null) {
        //validate field with ConfigDef annotation
        if(!validateConfigDefAnnotation(typeElement, variableElement, configDefAnnot)) {
          continue;
        }

        ModelDefinition model = null;

        if(configDefAnnot.type().equals(ConfigDef.Type.MODEL)) {
          FieldSelector fieldSelector = variableElement.getAnnotation(FieldSelector.class);
          if(fieldSelector != null) {
            model = new ModelDefinition(ModelType.FIELD_SELECTOR, null, null, null, null);
          }
          FieldValueChooser fieldValueChooser = variableElement.getAnnotation(FieldValueChooser.class);
          //processingEnv.
          if (fieldValueChooser != null) {
            model = new ModelDefinition(ModelType.FIELD_VALUE_CHOOSER,
                getFieldSelectionType(fieldValueChooser.type()),
                getValuesProvider(fieldValueChooser)
                , null, null);
          }
          ValueChooser valueChooser = variableElement.getAnnotation(ValueChooser.class);
          if(valueChooser != null) {
            model = new ModelDefinition(ModelType.VALUE_CHOOSER,
                getFieldSelectionType(valueChooser.type()),
                getValuesProvider(valueChooser)
                , null, null);
          }
        }

        ConfigDefinition configDefinition = new ConfigDefinition(
            variableElement.getSimpleName().toString(),
            configDefAnnot.type(),
            configDefAnnot.label(),
            configDefAnnot.description(),
            getDefaultValue(variableElement, configDefAnnot.defaultValue()),
            configDefAnnot.required(),
            ""/*group name - need to remove it*/,
            variableElement.getSimpleName().toString(),
            model);
        configDefinitions.add(configDefinition);
      }
    }
    return configDefinitions;
  }

  /**
   * Converts the argument string into the corresponding type.
   * If the argument cannot be converted to Integer or Long successfully a default value of 0 is returned.
   *
   * @param variableElement
   * @param defaultValue
   * @return
   */
  private Object getDefaultValue(VariableElement variableElement, String defaultValue) {
    TypeKind typeKind = variableElement.asType().getKind();
    if(typeKind.equals(TypeKind.BOOLEAN)) {
      return Boolean.parseBoolean(defaultValue);
    } else if (typeKind.equals(TypeKind.INT)) {
      try {
        return Integer.parseInt(defaultValue);
      } catch (NumberFormatException e) {
        return 0;
      }
    } else if (typeKind.equals(TypeKind.LONG)) {
      try {
        return Long.parseLong(defaultValue);
      } catch (NumberFormatException e) {
        return 0;
      }
    }
    //If String or Model, return the string as is
    return defaultValue;
  }

  private RawSourceDefinition getRawSourceDefinition(RawSource rawSourceAnnot) {
    //process all fields annotated with ConfigDef annotation in raw source provider
    String rawSourcePreviewerClass = getRawSourcePreviewer(rawSourceAnnot);
    TypeElement typeElement = getTypeElementFromName(rawSourcePreviewerClass);
    List<ConfigDefinition> configDefs = getConfigDefsFromTypeElement(typeElement);
    return new RawSourceDefinition(rawSourcePreviewerClass, rawSourceAnnot.mimeType(), configDefs);
  }

  /**
   * Returns all fields present in the class hierarchy of the type element
   * @param typeElement
   * @return
   */
  private List<VariableElement> getAllFields(TypeElement typeElement) {
    List<Element> enclosedElements = new ArrayList<>();
    enclosedElements.addAll(typeElement.getEnclosedElements());
    for(TypeMirror typeMirror : getAllSuperTypes(typeElement)) {
      //All super types are TypeElements, getAllSuperTypes method already does this check
      TypeElement t = (TypeElement)processingEnv.getTypeUtils().asElement(typeMirror);
      if(t.getEnclosedElements() != null) {
        enclosedElements.addAll(t.getEnclosedElements());
      }
    }
    List<VariableElement> variableElements = ElementFilter.fieldsIn(enclosedElements);
    return variableElements;
  }

  private ChooserMode getFieldSelectionType(com.streamsets.pipeline.api.ChooserMode type) {
    if(type.equals(com.streamsets.pipeline.api.ChooserMode.PROVIDED)) {
      return ChooserMode.PROVIDED;
    } else if (type.equals(com.streamsets.pipeline.api.ChooserMode.SUGGESTED)) {
      return ChooserMode.SUGGESTED;
    }
    //default
    return ChooserMode.SUGGESTED;
  }

  /**
   * Infers the type of stage based on the interface implemented or the
   * abstract class extended.
   *
   * @param typeElement the element from which the type must be extracted
   * @return the type
   */
  private String getStageTypeFromElement(TypeElement typeElement) {

    //Check if the stage extends one of the abstract classes
    for(TypeMirror typeMirror : getAllSuperTypes(typeElement)) {
      if (typeMirror.toString().equals("com.streamsets.pipeline.api.base.BaseSource")
        || typeMirror.toString().equals("com.streamsets.pipeline.api.Source")) {
        return SOURCE;
      } else if (typeMirror.toString().equals("com.streamsets.pipeline.api.base.BaseProcessor")
        || typeMirror.toString().equals("com.streamsets.pipeline.api.base.RecordProcessor")
        || typeMirror.toString().equals("com.streamsets.pipeline.api.base.SingleLaneProcessor")
        || typeMirror.toString().equals("com.streamsets.pipeline.api.base.SingleLaneRecordProcessor")
        || typeMirror.toString().equals("com.streamsets.pipeline.api.Processor")) {
        return PROCESSOR;
      } else if (typeMirror.toString().equals("com.streamsets.pipeline.api.base.BaseTarget")
        || typeMirror.toString().equals("com.streamsets.pipeline.api.Target")) {
        return TARGET;
      } else if (typeMirror.toString().equals("com.streamsets.pipeline.api.ErrorCode")) {
        return ERROR;
      }
    }
    return "";
  }

  /**
   * Recursively finds all super types of this element including the interfaces
   * @param element the element whose super types must be found
   * @return the list of super types in no particular order
   */
  private List<TypeMirror> getAllSuperTypes(TypeElement element){
    List<TypeMirror> allSuperTypes=new ArrayList<TypeMirror>();
    Queue<TypeMirror> runningList=new LinkedList<TypeMirror>();
    runningList.add(element.asType());
    while (runningList.size() != 0) {
      TypeMirror currentType=runningList.poll();
      if (currentType.getKind() != TypeKind.NONE) {
        allSuperTypes.add(currentType);
        Element currentElement= processingEnv.getTypeUtils().asElement(currentType);
        if (currentElement.getKind() == ElementKind.CLASS ||
          currentElement.getKind() == ElementKind.INTERFACE||
          currentElement.getKind() == ElementKind.ENUM) {
          TypeElement currentTypeElement=(TypeElement)currentElement;
          runningList.offer(currentTypeElement.getSuperclass());
          for(TypeMirror t : currentTypeElement.getInterfaces()) {
            runningList.offer(t);
          }
        }
      }
    }
    allSuperTypes.remove(element.asType());
    return allSuperTypes;
  }

  private void printError(String bundleKey,
                          String template, Object... args) {

    processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR,
      Utils.format(template, args));
  }

  private String getValuesProvider(FieldValueChooser fieldValueChooser) {
    //Not the best way of getting the TypeMirror of the ChooserValues implementation
    //Find a better solution
    TypeMirror valueProviderTypeMirror = null;
    try {
      fieldValueChooser.chooserValues();
    } catch (MirroredTypeException e) {
      valueProviderTypeMirror = e.getTypeMirror();
    }

    return getClassNameFromTypeMirror(valueProviderTypeMirror);
  }

  private String getClassNameFromTypeMirror(TypeMirror valueProviderTypeMirror) {
    List<String> outerClasses = new ArrayList<>();

    //check if the values provider is inner class
    //Make sure values provider implementation is top level
    Element e = processingEnv.getTypeUtils().asElement(valueProviderTypeMirror);
    Element enclosingElement = e.getEnclosingElement();
    while(!enclosingElement.getKind().equals(ElementKind.PACKAGE)) {
      outerClasses.add(enclosingElement.getSimpleName().toString());
      enclosingElement = enclosingElement.getEnclosingElement();
    }

    //append package name
    PackageElement packageElement = (PackageElement) enclosingElement;
    StringBuilder sb = new StringBuilder();
    sb.append(packageElement.getQualifiedName().toString());
    sb.append(DOT);
    //append outer class names followed by '$', in the reverse order
    if(!outerClasses.isEmpty()) {
      for(int i = outerClasses.size()-1 ; i >=0; i--) {
        sb.append(outerClasses.get(i)).append("$");
      }
    }

    //finally append the values provider name
    sb.append(getTypeElementFromMirror(valueProviderTypeMirror).getSimpleName().toString());

    return sb.toString();
  }

  private String getValuesProvider(ValueChooser valueChooser) {
    //Not the best way of getting the TypeMirror of the ChooserValues implementation
    //Find a better solution
    TypeMirror valueProviderTypeMirror = null;
    try {
      valueChooser.chooserValues();
    } catch (MirroredTypeException e) {
      valueProviderTypeMirror = e.getTypeMirror();
    }

    if(valueProviderTypeMirror !=null) {
      return getTypeElementFromMirror(valueProviderTypeMirror).getQualifiedName().toString();
    }
    return null;
  }

  private String getRawSourcePreviewer(RawSource rawSource) {
    //Not the best way of getting the TypeMirror of the ChooserValues implementation
    //Find a better solution
    TypeMirror rspTypeMirror = null;
    try {
      rawSource.rawSourcePreviewer();
    } catch (MirroredTypeException e) {
      rspTypeMirror = e.getTypeMirror();
    }

    if(rspTypeMirror !=null) {
      return getTypeElementFromMirror(rspTypeMirror).getQualifiedName().toString();
    }
    return null;
  }

  private TypeElement getTypeElementFromMirror(TypeMirror typeMirror) {
    return getTypeElementFromName(typeMirror.toString());
  }

  private TypeElement getTypeElementFromName(String qualifiedName) {
    return processingEnv.getElementUtils().getTypeElement(qualifiedName);
  }

  /**************************************************************************/
  /***************** Validation related methods *****************************/
  /**************************************************************************/

  /**
   * Validates that the stage definition implements the expected interface or
   * extends from the expected abstract base class.
   *
   * Also validates that the stage is not an inner class.
   *
   * @param typeElement
   */
  private boolean validateInterface(TypeElement typeElement) {
    Element enclosingElement = typeElement.getEnclosingElement();
    if(!enclosingElement.getKind().equals(ElementKind.PACKAGE)) {
      printError("stagedef.validation.not.outer.class",
        "Stage {} is an inner class. Inner class Stage implementations are not supported",
        typeElement.getSimpleName().toString());
    }
    if(getStageTypeFromElement(typeElement).isEmpty()) {
      //Stage does not implement one of the Stage interface or extend the base stage class
      //This must be flagged as a compiler error.
      printError("stagedef.validation.does.not.implement.interface",
        "Stage {} neither extends one of BaseSource, BaseProcessor, BaseTarget classes nor implements one of Source, Processor, Target interface.",
        typeElement.getQualifiedName().toString());
      //Continue for now to find out if there are more issues.
      return false;
    }
    return true;
  }

  /**
   * Validates the field on which the ConfigDef annotation is specified.
   * The following validations are done:
   * <ul>
   *   <li>The field must be declared as public</li>
   *   <li>The type of the field must match the type specified in the ConfigDef annotation</li>
   *   <li>If the type is "MODEL" then exactly one of "FieldSelector" or "FieldValueChooser" or "ValueChooser"
   *   annotation must be present</li>
   * </ul>
   *
   * @param variableElement
   * @param configDefAnnot
   */
  private boolean validateConfigDefAnnotation(Element typeElement, VariableElement variableElement,
                                              ConfigDef configDefAnnot) {
    boolean valid = true;
    //field must be declared public
    if(!variableElement.getModifiers().contains(Modifier.PUBLIC)) {
      printError("field.validation.not.public",
        "The field {} has \"ConfigDef\" annotation but is not declared public. Configuration fields must be declared public.",
        typeElement.getSimpleName().toString() + SEPARATOR + variableElement.getSimpleName().toString());
      valid = false;
    }
    //field must not be final
    if(variableElement.getModifiers().contains(Modifier.FINAL)) {
      printError("field.validation.final.field",
        "The field {} has \"ConfigDef\" annotation and is declared final. Configuration fields must not be declared final.",
        typeElement.getSimpleName().toString() + SEPARATOR + variableElement.getSimpleName().toString()
      );
      valid = false;
    }
    //field must not be static
    if(variableElement.getModifiers().contains(Modifier.STATIC)) {
      printError("field.validation.static.field",
        "The field {} has \"ConfigDef\" annotation and is declared static. Configuration fields must not be declared final.",
        typeElement.getSimpleName().toString() + SEPARATOR + variableElement.getSimpleName().toString());
      valid = false;
    }

    if(configDefAnnot.type().equals(ConfigDef.Type.MODEL)) {
      valid &= validateModelConfig(typeElement, variableElement);
    } else {
      valid &= validateModelAnnotationsAreNotPresent(typeElement, variableElement);
      valid &= validateNonModelConfig(configDefAnnot, typeElement, variableElement);
    }

    return valid;
  }

  private boolean validateModelAnnotationsAreNotPresent(Element typeElement, VariableElement variableElement) {
    FieldValueChooser fieldValueChooser = variableElement.getAnnotation(FieldValueChooser.class);
    FieldSelector fieldSelector = variableElement.getAnnotation(FieldSelector.class);
    ValueChooser valueChooser = variableElement.getAnnotation(ValueChooser.class);

    if(fieldValueChooser != null || fieldSelector != null || valueChooser != null) {
      printError("field.validation.model.annotations.present",
          "The type of field {} is not declared as \"MODEL\". 'FieldSelector' or 'FieldValueChooser' or " +
              "'ValueChooser' annotation is not expected, but is present.",
          typeElement.getSimpleName().toString() + SEPARATOR + variableElement.getSimpleName().toString());
      return false;
    }
    return true;
  }

  private boolean validateNonModelConfig(ConfigDef configDefAnnot, Element typeElement, VariableElement variableElement) {
    boolean valid = true;
    //type match
    TypeMirror fieldType = variableElement.asType();
    if (configDefAnnot.type().equals(ConfigDef.Type.BOOLEAN)) {
      if(!fieldType.getKind().equals(TypeKind.BOOLEAN)) {
        printError("field.validation.type.is.not.boolean",
            "The type of the field {} is expected to be boolean.",
            typeElement.getSimpleName().toString() + SEPARATOR + variableElement.getSimpleName().toString());
        valid = false;
      }
      if (fieldType.getKind().equals(TypeKind.BOOLEAN)) {
        String value = configDefAnnot.defaultValue();
        if(!value.equalsIgnoreCase("true") && !value.equalsIgnoreCase("false")) {
          printError("field.validation.default.value.not.boolean",
              "The type of the field {} is Boolean but the default value supplied is not true or false.",
              typeElement.getSimpleName().toString() + SEPARATOR + variableElement.getSimpleName().toString());
          valid = false;
        }
      }
    } else if (configDefAnnot.type().equals(ConfigDef.Type.INTEGER)) {
      if(!(fieldType.getKind().equals(TypeKind.INT) || fieldType.getKind().equals(TypeKind.LONG))) {
        printError("field.validation.type.is.not.int.or.long",
            "The type of the field {} is expected to be either an int or a long.",
            typeElement.getSimpleName().toString() + SEPARATOR + variableElement.getSimpleName().toString());
        valid = false;
      }
      //validate the default values
      if (fieldType.getKind().equals(TypeKind.INT)) {
        try {
          Integer.parseInt(configDefAnnot.defaultValue());
        } catch (NumberFormatException e) {
          printError("field.validation.default.value.not.int",
              "The type of the field {} is Integer but the default value supplied is not Integer.",
              typeElement.getSimpleName().toString() + SEPARATOR + variableElement.getSimpleName().toString());
          valid = false;
        }
      }
      if (fieldType.getKind().equals(TypeKind.LONG)) {
        try {
          Integer.parseInt(configDefAnnot.defaultValue());
        } catch (NumberFormatException e) {
          printError("field.validation.default.value.not.long",
              "The type of the field {} is Long but the default value supplied is not Long.",
              typeElement.getSimpleName().toString() + SEPARATOR + variableElement.getSimpleName().toString());
          valid = false;
        }
      }
    } else if (configDefAnnot.type().equals(ConfigDef.Type.STRING)) {
      if(!fieldType.toString().equals("java.lang.String")) {
        printError("field.validation.type.is.not.string",
            "The type of the field {} is expected to be String.",
            typeElement.getSimpleName().toString() + SEPARATOR + variableElement.getSimpleName().toString());
        valid = false;
      }
    }
    return valid;
  }

  /**
   * Validates FieldValueChooser, FieldSelector and ValueChooser annotations
   *
   * @param typeElement
   * @param variableElement
   * @return
   */
  private boolean validateModelConfig(Element typeElement, VariableElement variableElement) {
    boolean valid = true;
    FieldValueChooser fieldValueChooser = variableElement.getAnnotation(FieldValueChooser.class);
    FieldSelector fieldSelector = variableElement.getAnnotation(FieldSelector.class);
    ValueChooser valueChooser = variableElement.getAnnotation(ValueChooser.class);

    //Field is marked as model.
    //Carry out model related validations
    if(fieldValueChooser == null && fieldSelector == null && valueChooser == null) {
      printError("field.validation.no.model.annotation",
          "The type of field {} is declared as \"MODEL\". Exactly one of 'FieldSelector' or 'FieldValueChooser' or " +
              "'ValueChooser' annotation is expected.",
          typeElement.getSimpleName().toString() + SEPARATOR + variableElement.getSimpleName().toString());
      valid = false;
    }

    if (checkMultipleModelAnnot(fieldValueChooser, fieldSelector, valueChooser)) {
      printError("field.validation.multiple.model.annotations",
          "The type of field {} is declared as \"MODEL\". Exactly one of 'FieldSelector' or 'FieldValueChooser' or " +
              "'ValueChooser' annotation is expected.",
          typeElement.getSimpleName().toString() + SEPARATOR + variableElement.getSimpleName().toString());
      valid = false;
    } else {
      //Validate model annotations only if one of them is present
      //Otherwise it leads to confusing error messages
      if (fieldValueChooser != null) {
        valid &= validateFieldModifier(typeElement, variableElement, fieldValueChooser);
      }

      if (valueChooser != null) {
        valid &= validateDropDown(typeElement, variableElement, valueChooser);
      }

      if (fieldSelector != null) {
        valid &= validateFieldSelector(typeElement, variableElement, fieldSelector);
      }
    }
    return valid;
  }

  private boolean validateFieldSelector(Element typeElement, VariableElement variableElement, FieldSelector fieldSelector) {
    boolean valid = true;
    if (!variableElement.asType().toString().equals("java.util.List<java.lang.String>")) {
      printError("field.validation.type.is.not.list",
          "The type of the field {} is expected to be List<String>.",
          typeElement.getSimpleName().toString() + SEPARATOR + variableElement.getSimpleName().toString());
      valid = false;
    }
    return valid;
  }

  private boolean validateDropDown(Element typeElement, VariableElement variableElement, ValueChooser valueChooser) {
    boolean valid = true;

    if (!variableElement.asType().toString().equals("java.lang.String")) {
      printError("field.validation.type.is.not.String",
          "The type of the field {} is expected to be String.",
          typeElement.getSimpleName().toString() + SEPARATOR + variableElement.getSimpleName().toString());
      valid = false;
    }

    if(valueChooser.type().equals(com.streamsets.pipeline.api.ChooserMode.PROVIDED)) {
      //A chooserValues is expected.
      //check if the chooserValues is specified and that implements the correct base class
      //Not the best way of getting the TypeMirror of the ChooserValues implementation
      //Find a better solution
      TypeMirror valueProviderTypeMirror = null;
      try {
        valueChooser.chooserValues();
      } catch (MirroredTypeException e) {
        valueProviderTypeMirror = e.getTypeMirror();
      }

      valid &= validateValueProvider(typeElement, variableElement, valueProviderTypeMirror);
    }

    return valid;
  }

  private boolean validateFieldModifier(Element typeElement, VariableElement variableElement,
                                        FieldValueChooser fieldValueChooser) {
    boolean valid = true;
    TypeMirror fieldType = variableElement.asType();
    if (!fieldType.toString().equals("java.util.Map<java.lang.String,java.lang.String>")) {
      printError("field.validation.type.is.not.map",
          "The type of the field {} is expected to be Map<String, String>.",
          typeElement.getSimpleName().toString() + SEPARATOR + variableElement.getSimpleName().toString());
      valid = false;
    }

    if(fieldValueChooser.type().equals(com.streamsets.pipeline.api.ChooserMode.PROVIDED)) {
      //A chooserValues is expected.
      //check if the chooserValues is specified and that implements the correct base class
      //Not the best way of getting the TypeMirror of the ChooserValues implementation
      //Find a better solution
      TypeMirror valueProviderTypeMirror = null;
      try {
        fieldValueChooser.chooserValues();
      } catch (MirroredTypeException e) {
        valueProviderTypeMirror = e.getTypeMirror();
      }

      valid &= validateValueProvider(typeElement, variableElement, valueProviderTypeMirror);
    }
    return valid;
  }

  private boolean validateValueProvider(Element typeElement, VariableElement variableElement,
                                     TypeMirror valueProviderTypeMirror) {
    boolean valid = true;
    if (valueProviderTypeMirror == null) {
      printError("field.validation.chooserValues.not.supplied",
          "The field {} marked with ValueChooser annotation and the type is \"PROVIDED\" but no ChooserValues implementation is supplied",
          typeElement.getSimpleName().toString() + SEPARATOR + variableElement.getSimpleName().toString());
      valid = false;
    }
    //if ChooserValues is inner class then it must be static so that it can be instantiated using reflection
    Element e = processingEnv.getTypeUtils().asElement(valueProviderTypeMirror);
    Element enclosingElement = e.getEnclosingElement();
    if(!enclosingElement.getKind().equals(ElementKind.PACKAGE)) {
      //inner class, check for static modifier
      if(!e.getModifiers().contains(Modifier.STATIC)) {
        printError("chooserValues.inner.class.and.not.static",
            "ChooserValues implementation '{}' is an inner class but is not declared as static. " +
                "Inner class ChooserValues implementations must be declared static.",
            getClassNameFromTypeMirror(valueProviderTypeMirror));
        valid = false;
      }
    }

    return valid;
  }

  private boolean checkMultipleModelAnnot(FieldValueChooser fieldValueChooser, FieldSelector fieldSelector,
                                          ValueChooser valueChooser) {
    if(fieldValueChooser != null && (fieldSelector != null || valueChooser!= null)) {
      return true;
    }
    if(fieldSelector != null && valueChooser!= null) {
      return true;
    }
    return false;
  }

  /**
   * Validates that a stage definition with the same name and version is not
   * already encountered. If encountered, the "error" flag is set to true.
   *
   * If not, the current stage name and version is cached.
   *
   * @param stageDefAnnotation
   */
  private boolean validateAndCacheStageDef(TypeElement typeElement, StageDef stageDefAnnotation) {
    String stageName = StageHelper.getStageNameFromClassName(typeElement.getQualifiedName().toString());
    if(stageNameToVersionMap.containsKey(stageName) &&
      stageNameToVersionMap.get(stageName).equals(stageDefAnnotation.version())) {
      //found more than one stage with same name and version
      printError("stagedef.validation.duplicate.stages",
        "Multiple stage definitions found with the same name and version. Name {}, Version {}",
          stageName, stageDefAnnotation.version());
      //Continue for now to find out if there are more issues.
      return false;
    }
    stageNameToVersionMap.put(stageName, stageDefAnnotation.version());
    return true;
  }

  /**
   * Validates that the Stage definition is valid.
   * The following validations are done:
   * <ul>
   *   <li>The Stage implementation extends/implements the expected base classes/interfaces</li>
   *   <li>Stage implementation or its base class must declare a public constructor</li>
   * </ul>
   * @param typeElement
   * @param stageDefAnnotation
   * @return
   */
  private boolean validateStageDef(TypeElement typeElement, StageDef stageDefAnnotation) {
    boolean validInterface = validateInterface(typeElement);
    boolean validStage = validateAndCacheStageDef(typeElement, stageDefAnnotation);
    boolean validConstructor = validateStageForConstructor(typeElement);
    boolean validateIcon = validateIconExists(typeElement, stageDefAnnotation);
    boolean validateRawSource = validateRawSource(typeElement);

    return validInterface && validStage && validConstructor && validateIcon && validateRawSource;
  }

  private boolean validateRawSource(TypeElement typeElement) {
    boolean valid = true;
    //check if RawSource annotation is present on stage
    RawSource rawSourceAnnot = typeElement.getAnnotation(RawSource.class);
    if(rawSourceAnnot != null) {
      //make sure that the current stage is a Source
      String stageTypeFromElement = getStageTypeFromElement(typeElement);
      if(stageTypeFromElement == null || !SOURCE.equals(stageTypeFromElement)) {
        printError("rawSource.validation.not.applied.on.source",
            "Annotation RawSource is applied on stage {} which is not a \"Source\".",
            typeElement.getQualifiedName());
        valid = false;
      }
      //Not the best way of getting the TypeMirror of the ChooserValues implementation
      //Find a better solution
      TypeMirror rspTypeMirror = null;
      try {
        rawSourceAnnot.rawSourcePreviewer();
      } catch (MirroredTypeException e) {
        rspTypeMirror = e.getTypeMirror();
      }

      //Since the rawSourcePreviewer is mandatory property, it cannot be null
      //and the generics enforce that it has to implement the required interface.
      assert(rspTypeMirror != null);

      //Make sure raw source previewer implementation is top level
      Element e = processingEnv.getTypeUtils().asElement(rspTypeMirror);
      Element enclosingElement = e.getEnclosingElement();
      if(!enclosingElement.getKind().equals(ElementKind.PACKAGE)) {
        printError("rawSource.validation.rawSourcePreviewer.not.outer.class",
            "RawSourcePreviewer {} is an inner class. Inner class RawSourcePreviewer implementations are not supported.",
            rspTypeMirror.toString());
        valid = false;
      }
    }

    return valid;
  }

  /**
   * Validates that there exists a file as specified in the "icon" literal
   * of the StageDef annotation and that it ends with a ".svg" extension
   *
   * @param stageDefAnnotation
   * @return
   */
  private boolean validateIconExists(TypeElement typeElement, StageDef stageDefAnnotation) {
    // The following validations are done in the specified order in order to flag maximum
    // errors in one go
    //1. Check if the file exists
    //2. Access it
    //3. Check if it ends with .svg extension
    boolean valid = true;
    if(stageDefAnnotation.icon() != null && !stageDefAnnotation.icon().isEmpty()) {
      try {
        FileObject resource = processingEnv.getFiler().getResource(StandardLocation.CLASS_PATH, ""
          , stageDefAnnotation.icon());
        if (resource == null) {
          printError("stagedef.validation.icon.file.does.not.exist",
            "Stage Definition {} supplies an icon {} which does not exist.",
            typeElement.getQualifiedName(), stageDefAnnotation.icon());
          valid = false;
        }
      } catch (IOException e) {
        printError("stagedef.validation.cannot.access.icon.file",
          "Stage Definition {} supplies an icon {} which cannot be accessed for the following reason : {}.",
          typeElement.getQualifiedName(), stageDefAnnotation.icon(), e.getMessage());
        valid = false;
      }

      if (!stageDefAnnotation.icon().endsWith(".svg")) {
        printError("stagedef.validation.icon.not.svg",
          "Stage Definition {} supplies an icon {} which is not in an \"svg\" file.",
          typeElement.getQualifiedName(), stageDefAnnotation.icon());
        valid = false;
      }
    }
    return valid;
  }

  /**
   * A stage implementation should have a default constructor or no constructor.
   * In case, the stage implementation has no constructor, the same should
   * also apply to all its parent classes.
   *
   * @param typeElement
   * @return
   */
  private boolean validateStageForConstructor(TypeElement typeElement) {
    //indicates whether validation failed
    boolean validationError = false;
    //indicates whether default constructor was found
    boolean foundDefConstr = false;

    TypeElement te = typeElement;

    while(!foundDefConstr &&
      !validationError && te != null) {
      List<? extends Element> enclosedElements = te.getEnclosedElements();
      List<ExecutableElement> executableElements = ElementFilter.constructorsIn(enclosedElements);
      for(ExecutableElement e : executableElements) {
        //found one or more constructors, check for default constr
        if(e.getSimpleName().toString().equals(DEFAULT_CONSTRUCTOR)
          && e.getModifiers().contains(Modifier.PUBLIC)) {
          if(e.getParameters().size() == 0) {
            //found default constructor
            foundDefConstr = true;
            break;
          }
        }
      }
      //There are constructors but did not find default constructor
      if(executableElements.size() > 0 && !foundDefConstr) {
        validationError = true;
      }
      //get super class and run the same checks
      TypeMirror superClass = te.getSuperclass();
      if(superClass != null) {

        Element e = processingEnv.getTypeUtils().asElement(superClass);
        if(e != null && e.getKind().equals(ElementKind.CLASS)) {
          te = (TypeElement)e;
        } else {
          te = null;
        }
      } else {
        te = null;
      }
    }
    if(validationError) {
      printError("stage.validation.no.default.constructor",
        "The Stage {} has constructor with arguments but no default constructor.",
        typeElement.getSimpleName());
    }

    return !validationError;
  }

  /**
   * Validates the Stage Error Definition
   * Requires that it be enum which implements interface com.streamsets.pipeline.api.ErrorCode
   *
   * @param typeElement
   */
  private boolean validateErrorDefinition(TypeElement typeElement) {
    boolean valid = true;
    //must be enum
    if(typeElement.getKind() != ElementKind.ENUM) {
      //Stage does not implement one of the Stage interface or extend the base stage class
      //This must be flagged as a compiler error.
      printError("stagedeferror.validation.not.an.enum",
        "Stage Error Definition {} must be an enum", typeElement.getQualifiedName());
      valid = false;
    }
    //must implement com.streamsets.pipeline.api.ErrorCode
    String type = getStageTypeFromElement(typeElement);
    if(type.isEmpty() || !type.equals("ERROR")) {
      //Stage does not implement one of the Stage interface or extend the base stage class
      //This must be flagged as a compiler error.
      printError("stagedeferror.validation.enum.does.not.implement.interface",
        "Stage Error Definition {} does not implement interface 'com.streamsets.pipeline.api.ErrorCode'.",
        typeElement.getQualifiedName());
      valid = false;
    }
    errorEnumValidationFailure &= !valid;
    return valid;
  }

}