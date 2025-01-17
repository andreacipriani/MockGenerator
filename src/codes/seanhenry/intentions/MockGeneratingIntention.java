package codes.seanhenry.intentions;

import codes.seanhenry.util.*;
import com.intellij.codeInsight.hint.HintManager;
import com.intellij.codeInsight.intention.IntentionAction;
import com.intellij.codeInsight.intention.PsiElementBaseIntentionAction;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import com.intellij.psi.*;
import com.intellij.psi.codeStyle.CodeStyleManager;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.util.IncorrectOperationException;
import com.jetbrains.swift.psi.*;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.NotNull;

import java.util.*;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

public class MockGeneratingIntention extends PsiElementBaseIntentionAction implements IntentionAction {

  private Editor editor;
  private UniqueMethodNameGenerator methodNameGenerator;
  private final StringDecorator invokedPropertyNameDecorator = new PrependStringDecorator(null, "invoked");
  private final StringDecorator stubbedPropertyNameDecorator = new PrependStringDecorator(null, "stubbed");
  private final StringDecorator invokedMethodNameDecorator = new PrependStringDecorator(null, "invoked");
  private final StringDecorator stubMethodNameDecorator;
  private SwiftClassDeclaration classDeclaration;
  private SwiftFunctionDeclaration implementedFunction;
  private SwiftFunctionDeclaration protocolFunction;
  {
    StringDecorator prependDecorator = new PrependStringDecorator(null, "stubbed");
    stubMethodNameDecorator = new AppendStringDecorator(prependDecorator, "Result");
  }
  private final StringDecorator methodParametersNameDecorator;
  {
    StringDecorator prependDecorator = new PrependStringDecorator(null, "invoked");
    methodParametersNameDecorator = new AppendStringDecorator(prependDecorator, "Parameters");
  }
  private final StringDecorator stubbedClosureResultNameDecorator;
  {
    StringDecorator prependDecorator = new PrependStringDecorator(null, "stubbed");
    stubbedClosureResultNameDecorator = new AppendStringDecorator(prependDecorator, "Result");
  }

  private String scope = "";

  @Override
  public boolean isAvailable(@NotNull Project project, Editor editor, @NotNull PsiElement psiElement) {
    SwiftClassDeclaration classDeclaration = PsiTreeUtil.getParentOfType(psiElement, SwiftClassDeclaration.class);
    return classDeclaration != null;
  }

  @Override
  public void invoke(@NotNull Project project, Editor editor, @NotNull PsiElement psiElement) throws IncorrectOperationException {
    this.editor = editor;
    classDeclaration = PsiTreeUtil.getParentOfType(psiElement, SwiftClassDeclaration.class);
    if (classDeclaration == null) {
      showErrorMessage("Could not find a class to mock.");
      return;
    }
    SwiftTypeInheritanceClause inheritanceClause = classDeclaration.getTypeInheritanceClause();
    if (inheritanceClause == null) {
      showErrorMessage("Mock class does not inherit from anything.");
      return;
    }
    List<SwiftProtocolDeclaration> protocols = getResolvedProtocols(classDeclaration);
    if (protocols.isEmpty()) {
      showErrorMessage("Could not find a protocol reference.");
      return;
    }
    deleteClassStatements();
    scope = getMockScope();
    protocols = removeDuplicates(protocols);
    protocols = removeNSObjectProtocol(protocols);
    List<SwiftVariableDeclaration> properties = protocols
      .stream()
      .flatMap(p -> getProtocolProperties(p).stream())
      .collect(Collectors.toList());
    List<SwiftFunctionDeclaration> methods = protocols
      .stream()
      .flatMap(p -> getProtocolMethods(p).stream())
      .collect(Collectors.toList());
    List<SwiftAssociatedTypeDeclaration> associatedTypes = protocols
      .stream()
      .flatMap(p -> getProtocolAssociatedTypes(p).stream())
      .collect(Collectors.toList());
    addGenericParametersToClass(removeDuplicates(associatedTypes));
    addProtocolPropertiesToClass(removeDuplicates(properties));
    addProtocolFunctionsToClass(removeDuplicates(methods));

    CodeStyleManager codeStyleManager = CodeStyleManager.getInstance(psiElement.getManager());
    codeStyleManager.reformat(classDeclaration);
  }

  private String getMockScope() {
    if (classDeclaration.getAttributes().getText().contains("public")) {
      return "public ";
    }
    return "";
  }

  private List<SwiftProtocolDeclaration> removeNSObjectProtocol(List<SwiftProtocolDeclaration> protocols) {
    return protocols.stream().filter(p -> !p.getName().equals("NSObjectProtocol")).collect(Collectors.toList());
  }

  private <T> List<T> removeDuplicates(List<T> list) {
    return new ArrayList<>(new LinkedHashSet<>(list));
  }

  private void showErrorMessage(String message) {
    HintManager.getInstance().showErrorHint(editor, message);
  }

  private void deleteClassStatements() {
    for (SwiftStatement statement : classDeclaration.getStatementList()) {
      statement.delete();
    }
  }

  private List<SwiftFunctionDeclaration> getProtocolMethods(PsiElement protocol) {
    ElementGatheringVisitor<SwiftFunctionDeclaration> visitor = new ElementGatheringVisitor<>(SwiftFunctionDeclaration.class);
    protocol.accept(visitor);
    return visitor.getElements();
  }

  private List<SwiftVariableDeclaration> getProtocolProperties(PsiElement protocol) {
    ElementGatheringVisitor<SwiftVariableDeclaration> visitor = new ElementGatheringVisitor<>(SwiftVariableDeclaration.class);
    protocol.accept(visitor);
    return visitor.getElements();
  }

  private List<SwiftAssociatedTypeDeclaration> getProtocolAssociatedTypes(PsiElement protocol) {
    ElementGatheringVisitor<SwiftAssociatedTypeDeclaration> visitor = new ElementGatheringVisitor<>(SwiftAssociatedTypeDeclaration.class);
    protocol.accept(visitor);
    return visitor.getElements();
  }

  private List<SwiftProtocolDeclaration> getResolvedProtocols(SwiftTypeDeclaration typeDeclaration) {
    SwiftTypeInheritanceClause inheritanceClause = typeDeclaration.getTypeInheritanceClause();
    if (inheritanceClause == null) {
      return Collections.emptyList();
    }
    List<SwiftProtocolDeclaration> results = inheritanceClause.getReferenceTypeElementList()
      .stream()
      .map(this::getResolvedProtocol)
      .filter(Objects::nonNull)
      .collect(Collectors.toList());
    results.addAll(results
      .stream()
      .flatMap(p -> getResolvedProtocols(p).stream())
      .collect(Collectors.toList()));
    return results;
  }

  private SwiftProtocolDeclaration getResolvedProtocol(SwiftReferenceTypeElement reference) {
    PsiElement element = reference.resolve();
    if (element == null) {
      showErrorMessage("The protocol '" + reference.getName() + "' could not be found.");
      return null;
    }
    if (element instanceof SwiftProtocolDeclaration) {
      return (SwiftProtocolDeclaration) element;
    } else {
      showErrorMessage("This plugin currently only supports protocols.");
    }
    return null;
  }

  private void addProtocolFunctionsToClass(List<SwiftFunctionDeclaration> functions) {
    methodNameGenerator = new UniqueMethodNameGenerator(getMethodModels(functions));
    for (SwiftFunctionDeclaration function : functions) {
      protocolFunction = function;
      implementedFunction = createImplementedFunction();
      addInvokedCheckExpression();
      addInvokedParameterExpression();
      addCallToClosure();
      addReturnExpression();
      addInvocationCheckVariable();
      addInvokedParameterVariables();
      addClosureResultVariables();
      addReturnVariable();
      appendInClass(implementedFunction);
    }
  }

  private SwiftFunctionDeclaration createImplementedFunction() {
    List<String> params = getParameterNames(protocolFunction, p -> constructParameter(p), false);
    String literal = scope + "func " + protocolFunction.getName() + "(";
    literal += String.join(", ", params);
    literal += ")";
    if (protocolFunction.getFunctionResult() != null)
      literal += " " + protocolFunction.getFunctionResult().getText();
    literal += " { }";
    return getElementFactory().createFunction(literal);
  }

  private String constructParameter(SwiftParameter parameter) {

    List<String> labels = PsiTreeUtil.findChildrenOfAnyType(parameter, SwiftIdentifierPattern.class, SwiftWildcardPattern.class)
      .stream()
      .map(p -> p.getText())
      .collect(Collectors.toList());
    String labelString = String.join(" ", labels);
    return labelString + ": " + parameter.getParameterTypeAnnotation().getAttributes().getText() + " " + MySwiftPsiUtil.getResolvedTypeName(parameter, false);
  }

  private void addProtocolPropertiesToClass(List<SwiftVariableDeclaration> properties) {
    for (SwiftVariableDeclaration property : properties) {

      SwiftVariableDeclaration invokedProperty = new PropertyDecorator(invokedPropertyNameDecorator, PropertyDecorator.OPTIONAL, scope)
        .decorate(property);
      SwiftVariableDeclaration stubbedProperty = new PropertyDecorator(stubbedPropertyNameDecorator, PropertyDecorator.IMPLICITLY_UNWRAPPED_OPTIONAL, scope)
        .decorate(property);
      boolean hasSetter = PsiTreeUtil.findChildOfType(property, SwiftSetterClause.class) != null;
      String literal = buildConcreteProperty(property, invokedProperty, stubbedProperty, hasSetter);
      SwiftVariableDeclaration concreteProperty = (SwiftVariableDeclaration) getElementFactory().createStatement(literal);
      appendInClass(invokedProperty, hasSetter);
      appendInClass(stubbedProperty);
      appendInClass(concreteProperty);
    }
  }

  private void addGenericParametersToClass(List<SwiftAssociatedTypeDeclaration> associatedTypes) {

    if (associatedTypes.isEmpty()) {
      return;
    }
    if (classDeclaration.getGenericParameterClause() != null) {
      classDeclaration.getGenericParameterClause().delete();
    }
    String literal = "<";
    literal += associatedTypes.stream().map(PsiNamedElement::getName).collect(Collectors.joining(", "));
    literal += ">";
    SwiftStatement statement = getElementFactory().createStatement(literal);
    classDeclaration.addBefore(statement, classDeclaration.getTypeInheritanceClause());
  }

  @NotNull
  private String buildConcreteProperty(SwiftVariableDeclaration property,
                                       SwiftVariableDeclaration invokedProperty,
                                       SwiftVariableDeclaration stubbedProperty, boolean hasSetter) {
    SwiftTypeAnnotatedPattern pattern = (SwiftTypeAnnotatedPattern) property.getPatternInitializerList().get(0).getPattern();
    String attributes = property.getAttributes().getText();
    String label = pattern.getPattern().getText();
    String literal = scope + attributes + " var " + label + pattern.getTypeAnnotation().getText() + "{\n";
    String returnLabel = "return " + MySwiftPsiUtil.getName(stubbedProperty) + "\n";
    if (hasSetter) {
      literal += "set {\n" +
                 MySwiftPsiUtil.getName(invokedProperty) + " = newValue\n" +
                 "}\n";
      literal += "get {\n" +
                 returnLabel +
                 "}\n";
    } else {
      literal += returnLabel;
    }
    literal += "}";
    return literal;
  }

  private void appendInClass(PsiElement element) {
    appendInClass(element, true);
  }

  private void appendInClass(PsiElement element, boolean shouldAppend) {
    if (shouldAppend) {
      classDeclaration.addBefore(element, classDeclaration.getLastChild());
    }
  }

  private void appendInImplementedFunction(PsiElement element) {
    implementedFunction.getCodeBlock().addBefore(element, implementedFunction.getCodeBlock().getLastChild());
  }

  private void addInvocationCheckVariable() {
    SwiftStatement variable = getElementFactory().createStatement(scope + "var " + createInvokedVariableName() + " = false");
    appendInClass(variable);
  }

  private void addInvokedParameterVariables() {
    List<String> parameters = getParameterNames(protocolFunction, p -> {
      SwiftParameterTypeAnnotation typeAnnotation = p.getParameterTypeAnnotation();
      String name = p.getName() + ": " + MySwiftPsiUtil.getResolvedTypeName(typeAnnotation, true);
      if (MySwiftPsiUtil.containsOptionalOfType(typeAnnotation, SwiftReferenceTypeElement.class)) {
        return name + "?";
      }
      return name;
    }, true);
    if (parameters.isEmpty()) {
      return;
    } else if (parameters.size() == 1) {
      parameters.add("Void");
    }
    String variable = scope + "var " + createInvokedParametersName() + ": (" + String.join(", ", parameters) + ")?";
    SwiftStatement statement = getElementFactory().createStatement(variable);
    appendInClass(statement);
  }

  private void addClosureResultVariables() {
    List<SwiftParameter> parameters = getClosureParameters();
    for (SwiftParameter parameter : parameters) {
      String name = parameter.getName();
      List<String> types = getClosureParameterTypes(parameter);
      String variable = scope + "var " + createClosureResultName(name) + ": ";
      if (types.isEmpty()) {
        continue;
      } else if (types.size() == 1) {
        variable += types.get(0) + "?";
      } else {
        variable += "(" + String.join(", ", types) + ")?";
      }
      SwiftStatement statement = getElementFactory().createStatement(variable);
      appendInClass(statement);
    }
  }

  @NotNull
  private SwiftPsiElementFactory getElementFactory() {
    return SwiftPsiElementFactory.getInstance(classDeclaration);
  }

  private List<String> getClosureParameterTypes(SwiftParameter parameter) {
    SwiftFunctionTypeElement closure = MySwiftPsiUtil.findResolvedType(parameter, SwiftFunctionTypeElement.class);
    SwiftTupleTypeElement firstTuple = PsiTreeUtil.findChildOfType(closure, SwiftTupleTypeElement.class);
    return PsiTreeUtil.findChildrenOfType(firstTuple, SwiftTupleTypeItem.class).stream().map(t -> t.getTypeElement().getText())
      .collect(Collectors.toList());
  }

  private void addReturnVariable() {
    SwiftFunctionResult result = protocolFunction.getFunctionResult();
    if (result == null) {
      return;
    }
    String resultString = MySwiftPsiUtil.getResolvedTypeName(result);
    if (isClosure(result) && !result.getTypeElement().getText().startsWith("((")) {
      resultString = "(" + resultString + ")";
    }
    String name = createStubbedVariableName();
    String literal = scope + "var " + name + ": " + resultString + "!";
    SwiftStatement variable = getElementFactory().createStatement(literal);
    appendInClass(variable);
  }

  private void addInvokedCheckExpression() {
    SwiftExpression expression = getElementFactory().createExpression(createInvokedVariableName() + " = true ", protocolFunction);
    appendInImplementedFunction(expression);
  }

  private void addInvokedParameterExpression() {
    List<String> parameters = getParameterNames(protocolFunction, PsiNamedElement::getName, true);
    if (parameters.isEmpty()) {
      return;
    } else if (parameters.size() == 1) {
      parameters.add("()");
    }

    String string = createInvokedParametersName() + " = (" + String.join(", ", parameters) + ")";
    SwiftExpression expression = getElementFactory().createExpression(string, protocolFunction);
    appendInImplementedFunction(expression);
  }

  private void addCallToClosure() {
    for (SwiftParameter parameter : getClosureParameters()) {
      int count = getClosureParameterTypes(parameter).size();
      String name = parameter.getName();
      String closureCall;
      String optional = MySwiftPsiUtil.containsOptionalOfType(parameter, SwiftTupleTypeElement.class) ? "?" : "";
      if (count == 0) {
        closureCall = name + optional + "()";
      } else {
        closureCall = "if let result = " + createClosureResultName(name) + " {";
        closureCall += name + optional + "(";
        if(count == 1) {
          closureCall += "result";
        } else {
          closureCall += IntStream.range(0, count).mapToObj(i -> "result." + i).collect(Collectors.joining(","));
        }
        closureCall += ") }";

      }
      PsiElement statement = getElementFactory().createStatement(closureCall, protocolFunction);
      appendInImplementedFunction(statement);
    }
  }

  private void addReturnExpression() {
    if (protocolFunction.getFunctionResult() == null) {
      return;
    }
    SwiftStatement statement = getElementFactory().createStatement("return " + createStubbedVariableName());
    appendInImplementedFunction(statement);
  }

  private String createClosureResultName(String name) {
    return new PrependStringDecorator(stubbedClosureResultNameDecorator, protocolFunction.getName())
      .process(name);
  }

  private String createInvokedVariableName() {
    String name = methodNameGenerator.generate(getFunctionID(protocolFunction));
    return invokedMethodNameDecorator.process(name);
  }

  private String createStubbedVariableName() {
    String name = methodNameGenerator.generate(getFunctionID(protocolFunction));
    return stubMethodNameDecorator.process(name);
  }

  private String createInvokedParametersName() {
    String name = methodNameGenerator.generate(getFunctionID(protocolFunction));
    return methodParametersNameDecorator.process(name);
  }

  private List<String> getParameterNames(SwiftFunctionDeclaration function, Function<SwiftParameter, String> operation, boolean shouldRemoveClosures) {
    Predicate<? super SwiftParameter> filter = p -> true;
    if (shouldRemoveClosures) {
      filter = p -> !isClosure(p);
    }
    return function.getParameterClauseList().stream()
      .map(SwiftParameterClause::getParameterList)
      .flatMap(Collection::stream)
      .filter(filter)
      .map(operation)
      .collect(Collectors.toList());
  }

  private List<SwiftParameter> getClosureParameters() {
    return protocolFunction.getParameterClauseList().stream()
      .map(SwiftParameterClause::getParameterList)
      .flatMap(Collection::stream)
      .filter(this::isClosure)
      .collect(Collectors.toList());
  }

  private boolean isClosure(PsiElement parameter) {
    return getClosure(parameter) != null;
  }

  private SwiftFunctionTypeElement getClosure(PsiElement element) {
    return MySwiftPsiUtil.findResolvedType(element, SwiftFunctionTypeElement.class);
  }

  private List<UniqueMethodNameGenerator.MethodModel> getMethodModels(List<SwiftFunctionDeclaration> functions) {

    return functions.stream()
      .map(this::toMethodModel)
      .collect(Collectors.toList());
  }

  private UniqueMethodNameGenerator.MethodModel toMethodModel(SwiftFunctionDeclaration function) {
    return new UniqueMethodNameGenerator.MethodModel(
      getFunctionID(function),
      function.getName(),
      getParameterNames(function, p -> toParameterLabel(p), false).toArray(new String[]{})
    );
  }

  private String toParameterLabel(SwiftParameter parameter) {
    return parameter.getText();
  }

  private String getFunctionID(SwiftFunctionDeclaration function) {
    return function.getName() + String.join(":", getParameterNames(function, p -> p.getText(), false));
  }

  @Nls
  @NotNull
  @Override
  public String getFamilyName() {
    return getText();
  }

  @NotNull
  @Override
  public String getText() {
    return "Generate mock";
  }
}
