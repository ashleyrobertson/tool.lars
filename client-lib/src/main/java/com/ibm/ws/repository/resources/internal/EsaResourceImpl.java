/*******************************************************************************
 * Copyright (c) 2015 IBM Corp.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *******************************************************************************/

package com.ibm.ws.repository.resources.internal;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;

import com.ibm.ws.repository.common.enums.DisplayPolicy;
import com.ibm.ws.repository.common.enums.DownloadPolicy;
import com.ibm.ws.repository.common.enums.InstallPolicy;
import com.ibm.ws.repository.common.enums.ResourceType;
import com.ibm.ws.repository.common.enums.Visibility;
import com.ibm.ws.repository.connections.RepositoryConnection;
import com.ibm.ws.repository.exceptions.RepositoryResourceCreationException;
import com.ibm.ws.repository.resources.writeable.EsaResourceWritable;
import com.ibm.ws.repository.transport.model.AppliesToFilterInfo;
import com.ibm.ws.repository.transport.model.Asset;
import com.ibm.ws.repository.transport.model.JavaSEVersionRequirements;
import com.ibm.ws.repository.transport.model.WlpInformation;

public class EsaResourceImpl extends RepositoryResourceImpl implements EsaResourceWritable {

    /**
     * ----------------------------------------------------------------------------------------------------
     * INSTANCE METHODS
     * ----------------------------------------------------------------------------------------------------
     */

    /**
     * Constructor - requires connection info
     * 
     */
    public EsaResourceImpl(RepositoryConnection repoConnection) {
        this(repoConnection, null);
    }

    public EsaResourceImpl(RepositoryConnection repoConnection, Asset ass) {
        super(repoConnection, ass);

        if (ass == null) {
            setType(ResourceType.FEATURE);
            setDownloadPolicy(DownloadPolicy.INSTALLER);
            setInstallPolicy(InstallPolicy.MANUAL);
        }
    }

    /** {@inheritDoc} */
    @Override
    public void setProvideFeature(String feature) {
        if (_asset.getWlpInformation().getProvideFeature() != null) {
            _asset.getWlpInformation().setProvideFeature(null);
        }
        _asset.getWlpInformation().addProvideFeature(feature);
    }

    /** {@inheritDoc} */
    @Override
    public String getProvideFeature() {
        Collection<String> provideFeatures = _asset.getWlpInformation()
                        .getProvideFeature();
        if (provideFeatures == null || provideFeatures.isEmpty()) {
            return null;
        } else {
            return provideFeatures.iterator().next();
        }
    }

    /*
     * Uses the required features information to calculate a list of queries
     * stored as Strings that can be searched upon later
     * Input the list of required features information to convert into the query
     * Returns the list of queries (Strings)
     */
    private Collection<String> createEnablesQuery() {
        Collection<String> query = null;
        Collection<String> requiredFeatures = getRequireFeature();
        if (requiredFeatures != null) {
            query = new ArrayList<String>();
            for (String required : requiredFeatures) {
                String temp = "wlpInformation.provideFeature=" + required;

                String version = findVersion();
                if (version != null) {
                    temp += "&wlpInformation.appliesToFilterInfo.minVersion.value=";
                    temp += version;
                }

                temp += "&type=";
                temp += getType().getValue(); // get the long name of the Type 
                query.add(temp);
            }
        }
        return query;
    }

    /**
     * Uses the filter information to return the first version number
     * 
     * @return the first version number
     */
    private String findVersion() {
        WlpInformation wlp = _asset.getWlpInformation();
        if (wlp == null) {
            return null;
        }

        Collection<AppliesToFilterInfo> filterInfo = wlp.getAppliesToFilterInfo();
        if (filterInfo == null) {
            return null;
        }

        for (AppliesToFilterInfo filter : filterInfo) {
            if (filter.getMinVersion() != null) {
                return filter.getMinVersion().getValue();
            }
        }
        return null;
    }

    /*
     * Calculates the Enabled By query (required by features)
     */
    private Collection<String> createEnabledByQuery() {
        Collection<String> query = new ArrayList<String>();
        String temp = "";

        //generate queries
        temp = "wlpInformation.requireFeature=";
        temp += getProvideFeature();
        String version = findVersion();
        if (version != null) {
            temp += "&wlpInformation.appliesToFilterInfo.minVersion.value=";
            temp += version;
        }

        temp += "&type=";
        temp += getType().getValue();

        query.add(temp);
        return query;
    }

    /**
     * @return the query for Superseded By or null if this feature
     *         does not declare itself to be superseded by anything.
     */
    private Collection<String> createSupersededByQuery() {
        Collection<String> supersededBy = _asset.getWlpInformation().getSupersededBy();

        Collection<String> query = null;
        if (supersededBy != null) { //if there are no queries to add
            query = new ArrayList<String>();
            for (String feature : supersededBy) {
                StringBuilder b = new StringBuilder();
                b.append("wlpInformation.shortName=");
                b.append(feature);
                b.append("&wlpInformation.appliesToFilterInfo.minVersion.value=");
                String version = findVersion();
                if (version != null) {
                    b.append(version);
                    b.append("&type=com.ibm.websphere.Feature");
                }
                query.add(b.toString());
            }
        }

        return query;
    }

    /**
     * @return the Supersedes query. Note that this query will always be
     *         set to something because the website can't tell if this features
     *         supersedes anything without running the query. So this method
     *         won't ever return null.
     */
    private Collection<String> createSupersedesQuery() {
        String shortName = _asset.getWlpInformation().getShortName();
        if (shortName != null) {
            StringBuilder b = new StringBuilder();
            b.append("wlpInformation.supersededBy=");
            b.append(shortName);
            String version = findVersion();
            if (version != null) {
                b.append("&wlpInformation.appliesToFilterInfo.minVersion.value=");
                b.append(version);
            }
            return Arrays.asList(new String[] { b.toString() });
        } else {
            // if we get here then our shortname is null so we can't create a
            // query that refers to it.
            return null;
        }
    }

    /**
     * This generates the string that should be displayed on the website to indicate
     * the supported Java versions. The requirements come from the bundle manifests.
     * The mapping between the two is non-obvious, as it is the intersection between
     * the Java EE requirement and the versions of Java that Liberty supports.
     */
    private void addVersionDisplayString() {
        WlpInformation wlp = _asset.getWlpInformation();
        JavaSEVersionRequirements reqs = wlp.getJavaSEVersionRequirements();
        if (reqs == null) {
            return;
        }

        String minVersion = reqs.getMinVersion();

        // Null means no requirements specified which is fine
        if (minVersion == null) {
            return;
        }

        String requiresJava8 = "Java SE 8";
        String requiresJava7or8 = "Java SE 7, Java SE 8";
        String requiresJava6or7or8 = "Java SE 6, Java SE 7, Java SE 8";

        // The min version should have been validated when the ESA was constructed
        // so checking for the version string should be safe
        if (minVersion.equals("1.6.0")) {
            reqs.setVersionDisplayString(requiresJava6or7or8);
            return;
        }
        if (minVersion.equals("1.7.0")) {
            reqs.setVersionDisplayString(requiresJava7or8);
            return;
        }
        if (minVersion.equals("1.8.0")) {
            reqs.setVersionDisplayString(requiresJava8);
            return;
        }

        // The min version string has been generated/validated incorrectly
        // Can't recover from this, it is a bug in EsaUploader
        throw new AssertionError();

    }

    /**
     * @return the query for Superseded By (optional) or null if this feature
     *         does not declare itself to be optionally superseded by anything.
     */
    private Collection<String> createSupersededByOptionalQuery() {
        Collection<String> supersededByOptional = _asset.getWlpInformation().getSupersededByOptional();

        Collection<String> query = null;
        if (supersededByOptional != null) { //if there are no queries to add
            query = new ArrayList<String>();

            for (String feature : supersededByOptional) {
                StringBuilder b = new StringBuilder();
                b.append("wlpInformation.shortName=");
                b.append(feature);
                b.append("&wlpInformation.appliesToFilterInfo.minVersion.value=");
                String version = findVersion();
                if (version != null) {
                    b.append(version);
                    b.append("&type=com.ibm.websphere.Feature");
                }
                query.add(b.toString());
            }
        }

        return query;
    }

    private Link makeLink(String label, String linkLabelProperty, Collection<String> query, String linkLabelPrefix, String linkLabelSuffix) {
        Link link = makeLink(label, linkLabelProperty, query);
        link.setLinkLabelPrefix(linkLabelPrefix);
        link.setLinkLabelSuffix(linkLabelSuffix);
        return link;
    }

    private Link makeLink(String label, String linkLabelProperty, Collection<String> query) {
        Link link = new Link();
        link.setLabel(label);
        link.setLinkLabelProperty(linkLabelProperty);
        link.setQuery(query);
        return link;
    }

    /**
     * Creates the links to enables/enabled-by/supersedes/superseded-by sections. At present,
     * link labels are hardcoded in this function. We may need to move them in the future if
     * we need to translate them or if we just don't like the idea of having hardcoded message
     * strings in here.
     */
    private Collection<Link> createLinks() {
        ArrayList<Link> links = new ArrayList<Link>();

        Collection<String> enablesQuery = createEnablesQuery();
        links.add(makeLink("Features that this feature enables", "name", enablesQuery));

        Collection<String> enabledByQuery = createEnabledByQuery();
        links.add(makeLink("Features that enable this feature", "name", enabledByQuery));

        Collection<String> supersedesQuery = createSupersedesQuery();
        links.add(makeLink("Features that this feature supersedes", "name", supersedesQuery));

        Collection<String> supersededByQuery = createSupersededByQuery();
        links.add(makeLink("Features that supersede this feature", "name", supersededByQuery));

        // Note: by giving this the same link title as superseded-by, the links appear in the same
        // link section on the website (but with the suffix that we add here).
        Collection<String> supersededByOptionalQuery = createSupersededByOptionalQuery();
        links.add(makeLink("Features that supersede this feature", "name", supersededByOptionalQuery, null, " (optional)"));

        return links;
    }

    @Override
    public void updateGeneratedFields(boolean performEditionChecking) throws RepositoryResourceCreationException {
        super.updateGeneratedFields(performEditionChecking);

        setLinks(createLinks());

        // add the string the website will use for displaying java verison compatibility
        addVersionDisplayString();
    }

    protected Collection<AppliesToFilterInfo> getAppliesToFilterInfo() {
        return _asset.getWlpInformation().getAppliesToFilterInfo();
    }

    @Override
    public RepositoryResourceMatchingData createMatchingData() {
        ExtendedMatchingData matchingData = new ExtendedMatchingData();
        matchingData.setType(getType());

        // Regen the appliesToFilterInfo as the level of code that generated each resource may
        // be different and give us different results so regen it now.
        List<AppliesToFilterInfo> atfi;
        try {
            atfi = generateAppliesToFilterInfoList(false);
            matchingData.setAtfi(atfi);
        } catch (RepositoryResourceCreationException e) {
            // This should only be thrown if validate editions is set to true, for us its set to false
        }
        matchingData.setVersion(getVersion());
        matchingData.setProvideFeature(getProvideFeature());
        return matchingData;
    }

    @Override
    protected void copyFieldsFrom(RepositoryResourceImpl fromResource, boolean includeAttachmentInfo) {
        super.copyFieldsFrom(fromResource, includeAttachmentInfo);
        EsaResourceImpl esaRes = (EsaResourceImpl) fromResource;
        setAppliesTo(esaRes.getAppliesTo());
        setWebDisplayPolicy(esaRes.getWebDisplayPolicy());
        setInstallPolicy(esaRes.getInstallPolicy());
        setLinks(esaRes.getLinks());
        setProvideFeature(esaRes.getProvideFeature());
        setProvisionCapability(esaRes.getProvisionCapability());
        setRequireFeature(esaRes.getRequireFeature());
        setVisibility(esaRes.getVisibility());
        setShortName(esaRes.getShortName());
        setVanityURL(esaRes.getVanityURL());
    }

    @Override
    protected String getNameForVanityUrl() {
        return getProvideFeature();
    }

    /**
     * Returns the Enables {@link Links} for this feature
     * 
     * @return
     */
    public void setLinks(Collection<Link> links) {
        Collection<com.ibm.ws.repository.transport.model.Link> attachmentLinks = new ArrayList<com.ibm.ws.repository.transport.model.Link>();
        for (Link link : links) {
            attachmentLinks.add(link.getLink());
        }

        _asset.getWlpInformation().setLinks(attachmentLinks);
    }

    /**
     * Set the Enables {@link Links} for this feature
     * 
     * @return
     */
    public Collection<Link> getLinks() {
        Collection<com.ibm.ws.repository.transport.model.Link> attachmentLinks = _asset.getWlpInformation().getLinks();
        Collection<Link> links = new ArrayList<Link>();
        for (com.ibm.ws.repository.transport.model.Link link : attachmentLinks) {
            links.add(new Link(link));
        }
        return links;
    }

    /** {@inheritDoc} */
    @Override
    public void addRequireFeature(String requiredFeatureSymbolicName) {
        _asset.getWlpInformation().addRequireFeature(requiredFeatureSymbolicName);
    }

    /** {@inheritDoc} */
    @Override
    public void addRequireFix(String fix) {
        _asset.getWlpInformation().addRequireFix(fix);
    }

    /** {@inheritDoc} */
    @Override
    public Collection<String> getRequireFix() {
        return _asset.getWlpInformation().getRequireFix();
    }

    /** {@inheritDoc} */
    @Override
    public void setRequireFeature(Collection<String> feats) {
        _asset.getWlpInformation().setRequireFeature(feats);
    }

    /** {@inheritDoc} */
    @Override
    public Collection<String> getRequireFeature() {
        return _asset.getWlpInformation().getRequireFeature();
    }

    /** {@inheritDoc} */
    @Override
    public void addSupersededBy(String feature) {
        _asset.getWlpInformation().addSupersededBy(feature);
    }

    /** {@inheritDoc} */
    @Override
    public Collection<String> getSupersededBy() {
        return _asset.getWlpInformation().getSupersededBy();
    }

    /** {@inheritDoc} */
    @Override
    public void addSupersededByOptional(String feature) {
        _asset.getWlpInformation().addSupersededByOptional(feature);
    }

    /** {@inheritDoc} */
    @Override
    public Collection<String> getSupersededByOptional() {
        return _asset.getWlpInformation().getSupersededByOptional();
    }

    /**
     * Returns the {@link Visibility} for this feature
     * 
     * @return
     */
    @Override
    public Visibility getVisibility() {
        return _asset.getWlpInformation().getVisibility();
    }

    /** {@inheritDoc} */
    @Override
    public void setVisibility(Visibility vis) {
        _asset.getWlpInformation().setVisibility(vis);
    }

    /** {@inheritDoc} */
    @Override
    public void setShortName(String shortName) {
        _asset.getWlpInformation().setShortName(shortName);
    }

    /** {@inheritDoc} */
    @Override
    public String getShortName() {
        return _asset.getWlpInformation().getShortName();
    }

    /** {@inheritDoc} */
    @Override
    public String getLowerCaseShortName() {
        return _asset.getWlpInformation().getLowerCaseShortName();
    }

    /** {@inheritDoc} */
    @Override
    public void setAppliesTo(String appliesTo) {
        _asset.getWlpInformation().setAppliesTo(appliesTo);
    }

    /** {@inheritDoc} */
    @Override
    public String getAppliesTo() {
        return _asset.getWlpInformation().getAppliesTo();
    }

//    @Override
//    protected String getVersionForVanityUrl() {
//        String version = "";
//        WlpInformation wlp = _asset.getWlpInformation();
//        if (wlp != null) {
//            Collection<AppliesToFilterInfo> atfis = wlp.getAppliesToFilterInfo();
//            if (atfis != null && !atfis.isEmpty()) {
//                AppliesToFilterInfo atfi = atfis.iterator().next();
//                if (atfi != null) {
//                    FilterVersion ver = atfi.getMinVersion();
//                    if (ver != null) {
//                        version = ver.getLabel();
//                    }
//                }
//            }
//        }
//        return version;
//    }

    /** {@inheritDoc} */
    @Override
    public void setWebDisplayPolicy(DisplayPolicy policy) {
        _asset.getWlpInformation().setWebDisplayPolicy(policy);
    }

    /**
     * Get the {@link DisplayPolicy}
     * 
     * @return {@link DisplayPolicy} in use
     */
    @Override
    public DisplayPolicy getWebDisplayPolicy() {
        if (_asset.getWlpInformation() == null) {
            return null;
        }
        return _asset.getWlpInformation().getWebDisplayPolicy();
    }

    /** {@inheritDoc} */
    @Override
    public String getProvisionCapability() {
        return _asset.getWlpInformation().getProvisionCapability();
    }

    /** {@inheritDoc} */
    @Override
    public void setProvisionCapability(String provisionCapability) {
        _asset.getWlpInformation().setProvisionCapability(provisionCapability);
    }

    /** {@inheritDoc} */
    @Override
    public InstallPolicy getInstallPolicy() {
        if (_asset.getWlpInformation() == null) {
            return null;
        }
        return _asset.getWlpInformation().getInstallPolicy();
    }

    /** {@inheritDoc} */
    @Override
    public void setInstallPolicy(InstallPolicy policy) {
        _asset.getWlpInformation().setInstallPolicy(policy);
    }

    /** {@inheritDoc} */
    @Override
    public void setJavaSEVersionRequirements(String minimum, String maximum, Collection<String> rawBundleRequirements) {
        JavaSEVersionRequirements reqs = new JavaSEVersionRequirements();
        reqs.setMinVersion(minimum);
        reqs.setMaxVersion(maximum);
        reqs.setRawRequirements(rawBundleRequirements);
        _asset.getWlpInformation().setJavaSEVersionRequirements(reqs);
    }

    /**
     * An ESA may require a minimum or maximum Java version. This is an aggregate min/max,
     * calculated from the individual requirements of the contained bundles, as specified
     * by the bundles' Require-Capability header in the bundle manifest. The
     * <code>JavaSEVersionRequirements</code> contains the set of the Require-Capability
     * headers, i.e. one from each bundle which specifies the header.
     * All fields in the version object may be null, if no requirement was specified in the bundles.
     * 
     * @return
     */
    public JavaSEVersionRequirements getJavaSEVersionRequirements() {
        return _asset.getWlpInformation().getJavaSEVersionRequirements();
    }

}
