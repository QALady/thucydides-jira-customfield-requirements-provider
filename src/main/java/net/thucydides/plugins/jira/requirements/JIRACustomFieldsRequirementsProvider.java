package net.thucydides.plugins.jira.requirements;

import com.google.common.base.Optional;
import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import net.thucydides.core.guice.Injectors;
import net.thucydides.core.model.TestOutcome;
import net.thucydides.core.model.TestTag;
import net.thucydides.core.requirements.RequirementsTagProvider;
import net.thucydides.core.requirements.model.Requirement;
import net.thucydides.core.util.EnvironmentVariables;
import net.thucydides.plugins.jira.client.JerseyJiraClient;
import net.thucydides.plugins.jira.domain.IssueSummary;
import net.thucydides.plugins.jira.model.CascadingSelectOption;
import net.thucydides.plugins.jira.service.JIRAConfiguration;
import net.thucydides.plugins.jira.service.SystemPropertiesJIRAConfiguration;
import org.json.JSONException;
import org.slf4j.LoggerFactory;

import java.util.Collection;
import java.util.List;
import java.util.Set;

import static net.thucydides.core.ThucydidesSystemProperty.REQUIREMENT_TYPES;


/**
 * Integrate Thucydides reports with requirements using custom fields to define the requirements in JIRA.
 * This involves using a custom "Cascading Select" JIRA field that defines the various levels of requirements.
 * You need to specify what each requirement level is called in the thucydides.requirement.types property
 * (if not specified, 'capability/feature' will be assumed). You also need to specify what custom field is
 * used to represent the requirements, in the thucydides.requirements.custom.field property (the default is 'Requirements').
 * The plugin will look for this custom field in the Bug issue type by default. You can override this using
 * the 'thucydides.requirements.issue.type' property.
 *
 * Versions can also be obtained from JIRA custom fields. This is an alternative to using the Fixed Version field and the
 * built-in JIRA versions. Using this approach, a cascading select (called "Releases" by default) is used to define the
 * releases/iterations for
 */
public class JIRACustomFieldsRequirementsProvider implements RequirementsTagProvider {

    private List<Requirement> requirements = null;
    private final JerseyJiraClient jiraClient;
    private final String projectKey;
    private final String requirementsField;
    private final List<String> requirementTypes;

    public final static String ISSUETYPE_PROPERTY = "thucydides.requirements.issue.type";
    public final static String DEFAULT_ISSUETYPE = "Bug";

    public final static String CUSTOM_FIELD_PROPERTY = "thucydides.requirements.custom.field";
    public final static String DEFAULT_CUSTOM_FIELD = "Requirements";

    private final static String DEFAULT_REQUIREMENTS_TYPES = "capability, feature";

    private final org.slf4j.Logger logger = LoggerFactory.getLogger(JIRACustomFieldsRequirementsProvider.class);

    public JIRACustomFieldsRequirementsProvider() {
        this(new SystemPropertiesJIRAConfiguration(Injectors.getInjector().getInstance(EnvironmentVariables.class)),
             Injectors.getInjector().getInstance(EnvironmentVariables.class));
    }

    public JIRACustomFieldsRequirementsProvider(JIRAConfiguration jiraConfiguration,
                                                EnvironmentVariables environmentVariables) {
        logConnectionDetailsFor(jiraConfiguration);
        this.projectKey = jiraConfiguration.getProject();

        String issueType = environmentVariables.getProperty(ISSUETYPE_PROPERTY, DEFAULT_ISSUETYPE);
        requirementsField = environmentVariables.getProperty(CUSTOM_FIELD_PROPERTY, DEFAULT_CUSTOM_FIELD);
        requirementTypes = Splitter.on(",").trimResults().splitToList(
                                REQUIREMENT_TYPES.from(environmentVariables, DEFAULT_REQUIREMENTS_TYPES));

        jiraClient = new JerseyJiraClient(jiraConfiguration.getJiraUrl(),
                                          jiraConfiguration.getJiraUser(),
                                          jiraConfiguration.getJiraPassword(),
                                          projectKey)
                     .usingMetadataIssueType(issueType)
                     .usingCustomFields(ImmutableList.of(requirementsField));
    }

    private void logConnectionDetailsFor(JIRAConfiguration jiraConfiguration) {
        logger.debug("JIRA URL: {0}", jiraConfiguration.getJiraUrl());
        logger.debug("JIRA project: {0}", jiraConfiguration.getProject());
        logger.debug("JIRA user: {0}", jiraConfiguration.getJiraUser());
    }

    private String getProjectKey() {
        return projectKey;
    }

    @Override
    public List<Requirement> getRequirements() {
        if (requirements == null) {
            List<CascadingSelectOption> requirementsOptions = Lists.newArrayList();
            try {
                requirementsOptions = jiraClient.findOptionsForCascadingSelect(requirementsField);
            } catch (JSONException e) {
                logger.warn("No root requirements found", e);
            }
            requirements = convertToRequirements(requirementsOptions);
        }
        return requirements;
    }

    private List<Requirement> convertToRequirements(List<CascadingSelectOption> requirementsOptions) {
        return convertToRequirements(requirementsOptions, 0);
    }

    private List<Requirement> convertToRequirements(List<CascadingSelectOption> requirementsOptions, int requirementLevel) {
        List<Requirement> requirements = Lists.newArrayList();

        for(CascadingSelectOption option : requirementsOptions) {
            requirements.add(Requirement.named(option.getOption())
                    .withType(requirementType(requirementLevel))
                    .withNarrativeText(option.getOption())
                    .withChildren(convertToRequirements(option.getNestedOptions(), requirementLevel + 1)));

        }
        return requirements;
    }

    private String requirementType(int requirementLevel) {
        return (requirementLevel < requirementTypes.size()) ? requirementTypes.get(requirementLevel) : requirementTypes.get(requirementTypes.size() - 1);
    }


    //////////////////////////////////////

    @Override
    public Optional<Requirement> getParentRequirementOf(TestOutcome testOutcome) {
        List<String> issueKeys = testOutcome.getIssueKeys();
        if (!issueKeys.isEmpty()) {
            try {
                Optional<IssueSummary> parentIssue = jiraClient.findByKey(issueKeys.get(0));
                if (parentIssue.isPresent()) {
                    List<Requirement> requirements = requirementsCalled(parentIssue.get().getFieldValueList(requirementsField));
                    return Optional.of(requirements.get(requirements.size() - 1));
                } else {
                    return Optional.absent();
                }
            } catch (JSONException e) {
                if (noSuchIssue(e)) {
                    return Optional.absent();
                } else {
                    throw new IllegalArgumentException(e);
                }
            }
        } else {
            return Optional.absent();
        }
    }

    private List<Requirement> requirementsCalled(Optional<List<String>> fieldValueList) {
        if (fieldValueList.isPresent()) {
            List<Requirement> matchingRequirements = Lists.newArrayList();
            for(int level = 0; level < fieldValueList.get().size(); level++) {
                String optionValue = fieldValueList.get().get(level);
                matchingRequirements.add(Requirement.named(optionValue)
                                                    .withType(requirementType(level))
                                                    .withNarrativeText(optionValue));
            }
            return matchingRequirements;
        }
        return Lists.newArrayList();
    }

    private boolean noSuchIssue(JSONException e) {
        return e.getMessage().contains("error 400");
    }

    @Override
    public Optional<Requirement> getRequirementFor(TestTag testTag) {
        for (Requirement requirement : getFlattenedRequirements()) {
            if (requirement.getType().equals(testTag.getType()) && requirement.getName().equals(testTag.getName())) {
                return Optional.of(requirement);
            }
        }
        return Optional.absent();
    }

    @Override
    public Set<TestTag> getTagsFor(TestOutcome testOutcome) {
        List<String> issues  = testOutcome.getIssueKeys();
        Set<TestTag> tags = Sets.newHashSet();
        for(String issue : issues) {
            tags.addAll(tagsFromIssue(issue));
        }
        return ImmutableSet.copyOf(tags);
    }

    private Collection<? extends TestTag> tagsFromIssue(String issueKey) {
        CustomFieldIssueTagReader tagReader = new CustomFieldIssueTagReader(jiraClient, getFlattenedRequirements(), projectKey);
        return tagReader.addIssueTags(issueKey)
                        .addRequirementTags(issueKey)
                        .addVersionTags(issueKey).getTags();
    }

    private List<Requirement> getFlattenedRequirements(){
        return getFlattenedRequirements(getRequirements());
    }

    private List<Requirement> getFlattenedRequirements(List<Requirement> someRequirements){
        List<Requirement> flattenedRequirements = Lists.newArrayList();
        for (Requirement requirement : someRequirements) {
            flattenedRequirements.add(requirement);
            flattenedRequirements.addAll(getFlattenedRequirements(requirement.getChildren()));
        }
        return flattenedRequirements;
    }
}
