package net.thucydides.plugins.jira

import com.google.common.base.Optional
import net.thucydides.core.model.TestOutcome
import net.thucydides.core.model.TestTag
import net.thucydides.core.requirements.RequirementsTagProvider
import net.thucydides.core.requirements.model.Requirement
import net.thucydides.core.util.MockEnvironmentVariables
import net.thucydides.plugins.jira.requirements.JIRACustomFieldsRequirementsProvider
import net.thucydides.plugins.jira.service.JIRAConfiguration
import net.thucydides.plugins.jira.service.SystemPropertiesJIRAConfiguration
import spock.lang.Specification

class WhenReadingCustomFieldRequirementsFromJIRA extends Specification {


    JIRAConfiguration configuration
    def environmentVariables = new MockEnvironmentVariables()
    RequirementsTagProvider requirementsProvider

    def setup() {
        environmentVariables.setProperty('jira.url','https://wakaleo.atlassian.net')
        environmentVariables.setProperty('jira.username','bruce')
        environmentVariables.setProperty('jira.password','batm0bile')
        environmentVariables.setProperty('jira.project','DEMO')
        environmentVariables.setProperty('thucydides.requirement.types','capability,feature')

        configuration = new SystemPropertiesJIRAConfiguration(environmentVariables)
        requirementsProvider = new JIRACustomFieldsRequirementsProvider(configuration, environmentVariables)
    }

    def "should read requirement tree from JIRA"() {
        when:
            List<Requirement> requirements = requirementsProvider.getRequirements()
        then:
            requirements.collect { it.name } == ["Grow Apples","Grow Potatoes","Raise Chickens","Raise Sheep"]
        and:
            requirements.collect { it.type } == ["capability", "capability", "capability", "capability"]
        and:
            requirements[0].children.collect { it.name } == ["Grow red apples","Grow green apples"]
        and:
            requirements[0].children.collect { it.type } == ["feature", "feature"]
    }

    def "should get corresponding requirements from a test outcome "() {
        given:
            TestOutcome outcome = Mock(TestOutcome)
            outcome.issueKeys >> ["DEMO-8"]
        when:
            Optional<Requirement> requirement = requirementsProvider.getParentRequirementOf(outcome)
        then:
            requirement.isPresent()
        and:
            requirement.get().name == "Grow normal potatoes"
        and:
            requirement.get().type == "feature"
    }

    def "should get corresponding requirements from a test tag "() {
        given:
            TestTag tag = TestTag.withName("Grow Potatoes").andType("capability")
        when:
            Optional<Requirement> requirement = requirementsProvider.getRequirementFor(tag)
        then:
            requirement.isPresent()
        and:
            requirement.get().name == "Grow Potatoes"
    }
}
