package com.netflix.nebula.lint.rule

import com.netflix.nebula.lint.plugin.GradleLintPlugin
import com.netflix.nebula.lint.plugin.LintRuleRegistry
import com.netflix.nebula.lint.rule.test.AbstractRuleSpec
import org.codehaus.groovy.ast.expr.MethodCallExpression
import org.codehaus.groovy.ast.stmt.ExpressionStatement
import org.codenarc.rule.AbstractAstVisitorRule
import org.codenarc.rule.AstVisitor
import org.gradle.api.plugins.JavaPlugin
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import spock.lang.Unroll

class AbstractGradleLintVisitorSpec extends AbstractRuleSpec {
    @Rule
    TemporaryFolder temp

    def 'parse dependency map syntax'() {
        when:
        project.buildFile << """
            dependencies {
               compile group: 'junit', name: 'junit', version: '4.11'
            }
        """

        def rule = new SimpleLintRule()
        runRulesAgainst(rule)
        def visited = rule.visitor.visitedDeps[0]

        then:
        visited.group == 'junit'
        visited.name == 'junit'
        visited.version == '4.11'
        visited.syntax == GradleDependency.Syntax.MapNotation
    }

    def 'visit dependencies defined inside subprojects block'() {
        when:
        project.buildFile << """
            subprojects {
                dependencies {
                   compile 'junit:junit:4.11'
                }
            }
        """

        def rule = new SimpleLintRule()
        runRulesAgainst(rule)

        def visited = rule.visitor.visitedDeps[0]

        then:
        visited.group == 'junit'
        visited.name == 'junit'
        visited.version == '4.11'
        visited.syntax == GradleDependency.Syntax.StringNotation
    }

    def 'parse dependency string syntax'() {
        when:
        project.buildFile << """
            dependencies {
               compile 'junit:junit:4.11'
            }
        """

        def rule = new SimpleLintRule()
        runRulesAgainst(rule)
        def visited = rule.visitor.visitedDeps[0]

        then:
        visited.group == 'junit'
        visited.name == 'junit'
        visited.version == '4.11'
        visited.syntax == GradleDependency.Syntax.StringNotation
    }

    def 'apply plugin'() {
        when:
        project.buildFile << """
            apply plugin: 'nebula-dependency-lock'
        """

        def rule = new SimpleLintRule()
        runRulesAgainst(rule)
        def plugins = rule.visitor.appliedPlugins

        then:
        plugins == ['nebula-dependency-lock']
    }

    def 'add violation with deletion'() {
        when:
        def rule = new AbstractAstVisitorRule() {
            String name = 'no-apply-plugin'
            int priority = 2

            @Override
            AstVisitor getAstVisitor() {
                return new AbstractGradleLintVisitor() {
                    @Override
                    void visitApplyPlugin(MethodCallExpression call, String plugin) {
                        addViolationToDelete(call, "'apply plugin' syntax is not allowed")
                    }
                }
            }
        }

        project.buildFile << "apply plugin: 'java'"

        then:
        correct(rule) == ''
    }

    def 'add violation with insertion'() {
        when:
        def rule = new AbstractAstVisitorRule() {
            String name = 'no-apply-plugin'
            int priority = 2

            @Override
            AstVisitor getAstVisitor() {
                return new AbstractGradleLintVisitor() {
                    @Override
                    void visitApplyPlugin(MethodCallExpression call, String plugin) {
                        bookmark('lastApplyPlugin', call)
                    }

                    @Override
                    void visitGradleDependency(MethodCallExpression call, String conf, GradleDependency dep) {
                        if(bookmarks.lastApplyPlugin) {
                            addViolationInsert(call, 'should generate source jar', "\napply plugin: 'nebula.source-jar'", bookmarks.lastApplyPlugin)
                        }
                    }
                }
            }
        }

        project.buildFile << """
            apply plugin: 'java'

            dependencies {
                compile 'com.google.guava:guava:18.0'
            }
        """.stripIndent().trim()

        then:
        correct(rule) == """
            apply plugin: 'java'
            apply plugin: 'nebula.source-jar'

            dependencies {
                compile 'com.google.guava:guava:18.0'
            }
        """.stripIndent().trim()
    }

    @Unroll
    def 'violations are suppressed inside of ignore blocks when ignored rule(s) is `#rules`'() {
        setup:
        def rule = new AbstractAstVisitorRule() {
            String name = 'no-plugins-allowed'
            int priority = 2

            @Override
            AstVisitor getAstVisitor() {
                return new AbstractGradleLintVisitor() {
                    @Override
                    void visitApplyPlugin(MethodCallExpression call, String plugin) {
                        addViolationNoCorrection(call, 'no plugins allowed')
                    }
                }
            }
        }

        new File(temp.root, 'META-INF/lint-rules').mkdirs()
        def noPluginsProp = temp.newFile("META-INF/lint-rules/no-plugins-allowed.properties")
        noPluginsProp << "implementation-class=${rule.class.name}"
        LintRuleRegistry.classLoader = new URLClassLoader([temp.root.toURI().toURL()] as URL[], getClass().getClassLoader())

        when:
        project.buildFile << """
            gradleLint.ignore($rules) { apply plugin: 'java' }
        """

        def result = runRulesAgainst(rule)

        then:
        result.violates(rule.class) == violates

        where:
        rules                               |  violates
        ''                                  |  false
        /'no-plugins-allowed'/              |  false
        /'other-rule'/                      |  true
        /'no-plugins-allowed','other-rule'/ |  false
    }

    def 'ignore closure properly delegates'() {
        when:
        project.with {
            plugins.apply(JavaPlugin)
            plugins.apply(GradleLintPlugin)
            dependencies {
                gradleLint.ignore {
                    compile 'com.google.guava:guava:19.0'
                }
            }
        }

        then:
        project.configurations.compile.dependencies.any { it.name == 'guava' }
    }

    def 'visit extension properties'() {
        when:
        project.buildFile << """
            nebula {
                moduleOwner = 'me'
            }

            nebula.moduleOwner = 'me'

            subprojects {
                nebula {
                    moduleOwner = 'me'
                }
            }

            allprojects {
                nebula {
                    moduleOwner 'me' // sometimes this shorthand syntax is provided, notice no '='
                }
            }
        """

        def rule = new AbstractAstVisitorRule() {
            String name = 'nebula-module-owner'
            int priority = 2

            @Override
            AstVisitor getAstVisitor() {
                return new AbstractGradleLintVisitor() {
                    @Override
                    void visitExtensionProperty(ExpressionStatement expression, String extension, String prop) {
                        if(extension == 'nebula' && prop == 'moduleOwner')
                            addViolationToDelete(expression, 'moduleOwner is deprecated and should be removed')
                    }
                }
            }
        }

        def results = runRulesAgainst(rule)

        then:
        results.violates(rule.class)
        results.violations.size() == 4
    }

    static class SimpleLintVisitor extends AbstractGradleLintVisitor {
        List<GradleDependency> visitedDeps = []
        List<String> appliedPlugins = []

        @Override
        void visitGradleDependency(MethodCallExpression call, String conf, GradleDependency dep) {
            visitedDeps += dep
        }

        @Override
        void visitApplyPlugin(MethodCallExpression call, String plugin) {
            appliedPlugins += plugin
        }
    }

    static class SimpleLintRule extends AbstractAstVisitorRule {
        String name = 'SimpleLintRule'
        int priority = 3
        SimpleLintVisitor visitor = new SimpleLintVisitor()

        @Override
        AstVisitor getAstVisitor() { visitor }
    }
}