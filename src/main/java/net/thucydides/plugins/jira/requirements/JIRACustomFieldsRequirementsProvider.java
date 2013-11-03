package net.thucydides.plugins.jira.requirements;

import ch.lambdaj.function.convert.Converter;
import com.beust.jcommander.internal.Maps;
import com.google.common.base.Optional;
import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import net.thucydides.core.guice.Injectors;
import net.thucydides.core.model.Release;
import net.thucydides.core.model.TestOutcome;
import net.thucydides.core.model.TestTag;
import net.thucydides.core.requirements.ReleaseProvider;
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
import java.util.Map;
import java.util.Set;

import static ch.lambdaj.Lambda.convert;
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
 * built-in JIRA versions. Using this approach, a cascading select (called "Release" by default) is used to define the
 * releases/iterations for the project. This feature is deactivated by default, but can be activated
 * by setting the 'thucydides.use.customfield.releases' property to true. The field used can be configured using the
 * 'thucydides.releases.custom.field' property.
 */
public class JIRACustomFieldsRequirementsProvider implements RequirementsTagProvider, ReleaseProvider {

    private List<Requirement> requirements = null;
    private Map<Requirement, List<Requirement>> requirementAncestors = null;

    private final JerseyJiraClient jiraClient;
    private final String requirementsField;
    private final String releaseField;
    private final List<String> requirementTypes;

    private final boolean releaseProviderActive;

    public final static String DEFAULT_ISSUETYPE = "Bug";

    public final static String CUSTOM_FIELD_PROPERTY = "thucydides.requirements.custom.field";
    public final static String DEFAULT_CUSTOM_FIELD = "Requirements";

    public final static String ISSUETYPE_PROPERTY = "thucydides.requirements.issue.type";

    private final static String DEFAULT_REQUIREMENTS_TYPES = "capability, feature";

    public final static String USE_CUSTOMFIELD_RELEASES = "thucydides.use.customfield.releases";
    public final static String CUSTOMFIELD_RELEASES_PROPERTY = "thucydides.releases.custom.field";
    public final static String DEFAULT_RELEASE_FIELD = "Release";

    private final String STRINGS = "";

    private final org.slf4j.Logger logger = LoggerFactory.getLogger(JIRACustomFieldsRequirementsProvider.class);

    public JIRACustomFieldsRequirementsProvider() {
        this(new SystemPropertiesJIRAConfiguration(Injectors.getInjector().getInstance(EnvironmentVariables.class)),
             Injectors.getInjector().getInstance(EnvironmentVariables.class));
    }

    public JIRACustomFieldsRequirementsProvider(JIRAConfiguration jiraConfiguration,
                                                EnvironmentVariables environmentVariables) {
        logConnectionDetailsFor(jiraConfiguration);

        releaseProviderActive = environmentVariables.getPropertyAsBoolean(USE_CUSTOMFIELD_RELEASES, false);
        String issueType = environmentVariables.getProperty(ISSUETYPE_PROPERTY, DEFAULT_ISSUETYPE);
        requirementsField = environmentVariables.getProperty(CUSTOM_FIELD_PROPERTY, DEFAULT_CUSTOM_FIELD);
        releaseField = environmentVariables.getProperty(CUSTOMFIELD_RELEASES_PROPERTY, DEFAULT_RELEASE_FIELD);
        requirementTypes = Splitter.on(",").trimResults().splitToList(
                                REQUIREMENT_TYPES.from(environmentVariables, DEFAULT_REQUIREMENTS_TYPES));

        List<String> customFields = Lists.newArrayList(requirementsField);
        if (releaseProviderActive) {
            customFields.add(releaseField);
        }
        jiraClient = new JerseyJiraClient(jiraConfiguration.getJiraUrl(),
                                          jiraConfiguration.getJiraUser(),
                                          jiraConfiguration.getJiraPassword(),
                                          jiraConfiguration.getProject())
                     .usingMetadataIssueType(issueType)
                     .usingCustomFields(customFields);
    }

    private void logConnectionDetailsFor(JIRAConfiguration jiraConfiguration) {
        logger.debug("JIRA URL: {0}", jiraConfiguration.getJiraUrl());
        logger.debug("JIRA project: {0}", jiraConfiguration.getProject());
        logger.debug("JIRA user: {0}", jiraConfiguration.getJiraUser());
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

    private List<Release> releases;

    public List<Release> getReleases() {
        logger.info("Loading releases from JIRA custom fields");
        if (releases == null) {
            List<CascadingSelectOption> releaseOptions = Lists.newArrayList();
            try {
                releaseOptions = jiraClient.findOptionsForCascadingSelect(releaseField);
            } catch (JSONException e) {
                logger.warn("No releases found", e);
            }
            releases = new ReleaseConverter().convertToReleases(releaseOptions);
        }
        logger.info("Releases: " + releases);
        return releases;
    }



    @Override
    public boolean isActive() {
        return releaseProviderActive;
    }

    public Map<Requirement, List<Requirement>> getRequirementAncestors() {
        if (requirementAncestors == null) {
            requirementAncestors = indexAncestors();
        }
        return requirementAncestors;
    }

    private static List<Requirement> NO_REQUIREMENTS = Lists.newArrayList();

    private Map<Requirement, List<Requirement>> indexAncestors() {
        requirementAncestors = Maps.newHashMap();
        for(Requirement requirement : getRequirements()) {
            requirementAncestors.put(requirement, NO_REQUIREMENTS);
            indexChildren(ImmutableList.of(requirement), requirement.getChildren(), requirementAncestors);
        }
        return requirementAncestors;
    }

    private void indexChildren(List<Requirement> parents, List<Requirement> children, Map<Requirement, List<Requirement>> requirementAncestors) {
        for(Requirement child : children) {
            requirementAncestors.put(child, parents);
            List<Requirement> parentsAndChild = Lists.newArrayList(parents);
            parentsAndChild.add(child);
            indexChildren(parentsAndChild, child.getChildren(), requirementAncestors);
        }
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
            return getParentRequirementByIssueKey(issueKeys.get(0));
        } else {
            return Optional.absent();
        }
    }

    private Optional<Requirement> getParentRequirementByIssueKey(String issueKey) {
        try {
            Optional<IssueSummary> parentIssue = jiraClient.findByKey(issueKey);
            if (parentIssue.isPresent()  && parentIssue.get().customField(requirementsField).isPresent()) {
                List<String> requirementNames = parentIssue.get().customField(requirementsField).get().asListOf(STRINGS);
                List< Requirement > requirements = requirementsCalled(requirementNames);
                if (!requirements.isEmpty()) {
                    return Optional.of(requirements.get(requirements.size() - 1));
                }
            }
        } catch (JSONException e) {
            if (noSuchIssue(e)) {
                return Optional.absent();
            } else {
                throw new IllegalArgumentException(e);
            }
        }
        return Optional.absent();
    }

    public List<Requirement> getAssociatedRequirements(TestOutcome testOutcome) {
        List<Requirement> associatedRequirements = Lists.newArrayList();
        Optional<Requirement> parent = getParentRequirementOf(testOutcome);
        if (parent.isPresent()) {
            associatedRequirements.add(parent.get());
            associatedRequirements.addAll(parentsOf(parent.get()));
        }
        return associatedRequirements;
    }

    private List<? extends Requirement> parentsOf(Requirement requirement) {
        if (getRequirementAncestors().containsKey(requirement)) {
            return getRequirementAncestors().get(requirement);
        } else {
            return NO_REQUIREMENTS;
        }
    }

    private List<Requirement> requirementsCalled(List<String> fieldValueList) {
        List<Requirement> matchingRequirements = Lists.newArrayList();
        for(int level = 0; level < fieldValueList.size(); level++) {
            String optionValue = fieldValueList.get(level);
            matchingRequirements.add(Requirement.named(optionValue)
                                                .withType(requirementType(level))
                                                .withNarrativeText(optionValue));
        }
        return matchingRequirements;
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

    private Collection<TestTag> tagsFromIssue(String issueKey) {

        List<TestTag> matchingTags = getRequirementsTags(issueKey);

        Optional<IssueSummary> issue = Optional.absent();
        try {
            issue = jiraClient.findByKey(issueKey);
        } catch (JSONException e) {
            logger.error("Could not load issue: " + issueKey, e);
        }
        if (issue.isPresent()) {
            matchingTags.addAll(getCustomVersionTags(issue.get()));
            matchingTags.add(TestTag.withName(issue.get().getSummary()).andType(issue.get().getType()));
            if (releaseProviderActive) {
               matchingTags.addAll(getCustomVersionTags(issue.get()));
            } else {
                matchingTags.addAll(versionTagsFrom(issue.get().getFixVersions()));
            }
        }
        return matchingTags;
    }

    private List<TestTag> versionTagsFrom(List<String> versions) {
        List<TestTag> matchingTags = Lists.newArrayList();
        for(String version : versions) {
            TestTag versionTag = TestTag.withName(version).andType("Version");
            matchingTags.add(versionTag);
        }
        return matchingTags;
    }

    private List<TestTag> getCustomVersionTags(IssueSummary issue) {
        List<TestTag> versionTags = Lists.newArrayList();

        if (issue.customField(releaseField).isPresent()) {
            List<String> versions = issue.customField(releaseField).get().asListOf(STRINGS);
            for(String version : versions) {
                versionTags.add(TestTag.withName(version).andType("version"));
            }
        }
        return versionTags;
    }

    private List<TestTag> getRequirementsTags(String issueKey) {
        List<TestTag> matchingTags = Lists.newArrayList();
        Optional<Requirement> parentRequirement = getParentRequirementByIssueKey(issueKey);
        if (parentRequirement.isPresent()) {
            List<Requirement> associatedRequirements = Lists.newArrayList(parentRequirement.get());
            associatedRequirements.addAll(parentsOf(parentRequirement.get()));
            matchingTags.addAll(requirementsTagsFrom(associatedRequirements));
        }
        return matchingTags;
    }

    private List<TestTag> requirementsTagsFrom(List<Requirement> requirements) {
        return convert(requirements, toTestTags());
    }

    private Converter<Requirement, TestTag> toTestTags() {
        return new Converter<Requirement, TestTag>() {
            @Override
            public TestTag convert(Requirement from) {
                return TestTag.withName(from.getName()).andType(from.getType());
            }
        };
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
