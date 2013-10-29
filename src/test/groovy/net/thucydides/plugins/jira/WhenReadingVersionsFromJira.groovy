package net.thucydides.plugins.jira

import net.thucydides.core.model.TestOutcome
import net.thucydides.core.model.TestTag
import net.thucydides.core.requirements.model.Requirement
import net.thucydides.core.util.MockEnvironmentVariables
import net.thucydides.plugins.jira.requirements.JIRACustomFieldsRequirementsProvider
import net.thucydides.plugins.jira.requirements.JIRARequirementsProvider
import net.thucydides.plugins.jira.service.JIRAConfiguration
import net.thucydides.plugins.jira.service.SystemPropertiesJIRAConfiguration
import spock.lang.Specification

class WhenReadingVersionsFromJira extends Specification {


    JIRAConfiguration configuration
    def environmentVariables = new MockEnvironmentVariables()
    def requirementsProvider

    def setup() {
        environmentVariables.setProperty('jira.url','https://wakaleo.atlassian.net')
        environmentVariables.setProperty('jira.username','bruce')
        environmentVariables.setProperty('jira.password','batm0bile')
        environmentVariables.setProperty('jira.project','DEMO')

        configuration = new SystemPropertiesJIRAConfiguration(environmentVariables)
        requirementsProvider = new JIRACustomFieldsRequirementsProvider(configuration, environmentVariables)
    }

    def "should find version details for a given issue"() {
        given:
            def testOutcome = Mock(TestOutcome)
            testOutcome.getIssueKeys() >> ["DEMO-2"]
        when:
            def tags = requirementsProvider.getTagsFor(testOutcome)
        then:
            tags.size() == 3
        and:
            tags.contains(TestTag.withName("Iteration 1.1").andType("Version"))
        and:
            tags.contains(TestTag.withName("Version 1.0").andType("Version"))
    }

}
