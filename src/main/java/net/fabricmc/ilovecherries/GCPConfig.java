package net.fabricmc.ilovecherries;

public class GCPConfig {
    private String repoURL;

    public GCPConfig(String repoURL) {
        this.repoURL = repoURL;
    }

    public String getRepoURL() {
        return repoURL;
    }

    public void setRepoURL(String repoURL) {
        this.repoURL = repoURL;
    }
}
