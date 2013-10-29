package net.thucydides.plugins.jira.requirements;

import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import net.thucydides.core.model.TestTag;
import net.thucydides.core.requirements.model.Requirement;
import net.thucydides.plugins.jira.client.JerseyJiraClient;
import net.thucydides.plugins.jira.domain.IssueSummary;
import org.apache.commons.lang3.StringUtils;
import org.json.JSONException;
import org.slf4j.LoggerFactory;

import java.util.List;

public class CustomFieldIssueTagReader {

    private final org.slf4j.Logger logger = LoggerFactory.getLogger(CustomFieldIssueTagReader.class);

    private final List<Requirement> flattenedRequirements;
    private final JerseyJiraClient jiraClient;
    private final String projectKey;
    private List<TestTag> tags = Lists.newArrayList();

    public CustomFieldIssueTagReader(JerseyJiraClient jiraClient, List<Requirement> flattenedRequirements, String projectKey) {
        this.flattenedRequirements = flattenedRequirements;
        this.jiraClient = jiraClient;
        this.projectKey = projectKey;
    }

    public CustomFieldIssueTagReader addVersionTags(String issueKey) {
        String decodedIssueKey = decoded(issueKey);
        try {
            Optional<IssueSummary> issue = jiraClient.findByKey(issueKey);
            if (issue.isPresent()) {
                addVersionTags(issue.get().getFixVersions());
            }
        } catch (JSONException e) {
            logger.warn("Could not read versions for issue " + decodedIssueKey, e);
        }
        return this;
    }

    private void addVersionTags(List<String> versions) {
        for(String version : versions) {
            TestTag versionTag = TestTag.withName(version).andType("Version");
            tags.add(versionTag);
        }
    }

    public CustomFieldIssueTagReader addRequirementTags(String issueKey) {
        String decodedIssueKey = decoded(issueKey);
        List<Requirement> parentRequirements = getParentRequirementsOf(decodedIssueKey);
        for (Requirement parentRequirement : parentRequirements) {
            TestTag parentTag = TestTag.withName(parentRequirement.getName())
                    .andType(parentRequirement.getType());
            tags.add(parentTag);
        }
        return this;
    }

    public CustomFieldIssueTagReader addIssueTags(String issueKey) {
        String decodedIssueKey = decoded(issueKey);
        Optional<IssueSummary> behaviourIssue = Optional.absent();
        try {
            behaviourIssue = jiraClient.findByKey(decodedIssueKey);
        } catch (JSONException e) {
            logger.warn("Could not read tags for issue " + decodedIssueKey, e);
        }
        if (behaviourIssue.isPresent()) {
            tags.add(TestTag.withName(behaviourIssue.get().getSummary()).andType(behaviourIssue.get().getType()));
        }
        return this;
    }

    public List<TestTag> getTags() {
        return ImmutableList.copyOf(tags);
    }

    private List<Requirement> getParentRequirementsOf(String issueKey) {
        List<Requirement> parentRequirements = Lists.newArrayList();

        Optional<Requirement> parentRequirement = getParentRequirementOf(issueKey);
        if (parentRequirement.isPresent()) {
            parentRequirements.add(parentRequirement.get());
            parentRequirements.addAll(getParentRequirementsOf(parentRequirement.get().getCardNumber()));
        }

        return parentRequirements;
    }

    private Optional<Requirement> getParentRequirementOf(String key) {
        for (Requirement requirement : flattenedRequirements) {
            if (containsRequirementWithId(key, requirement.getChildren())) {
                return Optional.of(requirement);
            }
        }
        return Optional.absent();
    }

    private boolean containsRequirementWithId(String key, List<Requirement> requirements) {
        for (Requirement requirement : requirements) {
            if (requirement.getCardNumber().equals(key)) {
                return true;
            }
        }
        return false;
    }

    private String decoded(String issueKey) {
        if (issueKey.startsWith("#")) {
            issueKey = issueKey.substring(1);
        }
        if (StringUtils.isNumeric(issueKey)) {
            issueKey = projectKey + "-" + issueKey;
        }
        return issueKey;
    }

}