package com.vincent.llc.dns.manager.service;

import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;

import org.apache.commons.lang3.ObjectUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.viescloud.llc.viesspringutils.exception.HttpResponseThrowers;
import com.viescloud.llc.viesspringutils.util.DateTime;
import com.vincent.llc.dns.manager.model.DnsRecord;
import com.vincent.llc.dns.manager.model.cloudflare.CloudflareRequest;
import com.vincent.llc.dns.manager.model.cloudflare.CloudflareResult;
import com.vincent.llc.dns.manager.model.nginx.NginxCertificateResponse;
import com.vincent.llc.dns.manager.model.nginx.NginxProxyHostResponse;

@Service
public class DnsService {
    public static final String VIESCLOUD_DOMAIN = "viescloud.com";
    public static final String VIESLOCAL_DOMAIN = "vieslocal.com";

    @Autowired
    private PublicNginxService publicNginxService;

    @Autowired
    private LocalNginxService localNginxService;

    @Autowired
    private ViescloudCloudflareService viescloudCloudflareService;

    @Autowired
    private VieslocalCloudflareService vieslocalCloudflareService;

    public void clearDnsRecordsCache() {
        this.publicNginxService.clearCache();
        this.localNginxService.clearCache();
        this.viescloudCloudflareService.clearCache();
        this.vieslocalCloudflareService.clearCache();
    }

    public List<DnsRecord> getDnsRecordList() {
        return new ArrayList<>(this.getDnsRecordMap().values());
    }

    public Map<String, DnsRecord> getDnsRecordMap() {
        var recordMap = new HashMap<String, DnsRecord>();
        var dnsMap = new HashMap<String, String>();
        this.fetchAllPublicNginxDnsRecords(recordMap, dnsMap);
        this.fetchAllLocalNginxDnsRecords(recordMap, dnsMap);
        this.fetchAllCloudflareViescloudDnsRecords(recordMap, dnsMap);
        this.fetchAllCloudflareVieslocalDnsRecords(recordMap, dnsMap);
        return recordMap;
    }

    @SuppressWarnings("unchecked")
    public List<NginxCertificateResponse> getAllNginxCertificate(String type) {
        switch (type) {
            case VIESCLOUD_DOMAIN:
                return this.publicNginxService.getAllCertificate();
            case VIESLOCAL_DOMAIN:
                return this.localNginxService.getAllCertificate();
            default:
                return (List<NginxCertificateResponse>) HttpResponseThrowers.throwBadRequest(type + " is not a valid domain name.");
        }
    }

    private void fetchAllCloudflareVieslocalDnsRecords(Map<String, DnsRecord> recordMap, Map<String, String> dnsMap) {
        this.fetchAllCloudflareDnsRecords(recordMap, dnsMap, this.vieslocalCloudflareService, (dns, record) -> {
            record.getCloudflareViesLocalRecord().add(dns);
            return record;
        });
    }

    private void fetchAllCloudflareViescloudDnsRecords(Map<String, DnsRecord> recordMap, Map<String, String> dnsMap) {
        this.fetchAllCloudflareDnsRecords(recordMap, dnsMap, this.viescloudCloudflareService, (dns, record) -> {
            record.getCloudflareViescloudRecord().add(dns);
            return record;
        });
    }

    private void fetchAllCloudflareDnsRecords(Map<String, DnsRecord> recordMap, Map<String, String> dnsMap,
            CloudflareService cloudflareService, BiFunction<CloudflareResult, DnsRecord, DnsRecord> function) {
        var list = cloudflareService.getAllCloudflareCnameRecord();

        list.forEach(dns -> {

            if (!dnsMap.containsKey(dns.getName())) {
                return;
            }

            var url = dnsMap.get(dns.getName());

            DnsRecord record = null;

            if (!recordMap.containsKey(url)) {
                record = new DnsRecord();
                record.setUri(URI.create(url));
                recordMap.put(url, record);
            } else {
                record = recordMap.get(url);
            }

            record = function.apply(dns, record);
        });
    }

    private void fetchAllPublicNginxDnsRecords(Map<String, DnsRecord> recordMap, Map<String, String> dnsMap) {
        this.fetchAllNginxDnsRecords(recordMap, dnsMap, this.publicNginxService, (proxyHost, record) -> {
            record.setPublicNginxRecord(proxyHost);
            return record;
        });
    }

    private void fetchAllLocalNginxDnsRecords(Map<String, DnsRecord> recordMap, Map<String, String> dnsMap) {
        this.fetchAllNginxDnsRecords(recordMap, dnsMap, this.localNginxService, (proxyHost, record) -> {
            record.setLocalNginxRecord(proxyHost);
            return record;
        });
    }

    private void fetchAllNginxDnsRecords(Map<String, DnsRecord> recordMap, Map<String, String> dnsMap,
            NginxService service, BiFunction<NginxProxyHostResponse, DnsRecord, DnsRecord> function) {
        var proxyHosts = service.getAllProxyHost();

        proxyHosts.forEach(proxyHost -> {
            String url = String.format("%s://%s:%s", proxyHost.getForwardScheme(), proxyHost.getForwardHost(),
                    proxyHost.getForwardPort());
            DnsRecord record = null;

            if (!recordMap.containsKey(url)) {
                record = new DnsRecord();
                record.setUri(URI.create(url));
                recordMap.put(url, record);
            } else {
                record = recordMap.get(url);
            }

            record = function.apply(proxyHost, record);

            proxyHost.getDomainNames().forEach(domainName -> {
                if (!dnsMap.containsKey(domainName))
                    dnsMap.put(domainName, url);
            });
        });
    }

    public void putDnsRecordList(List<DnsRecord> recordList) {
        recordList.forEach(this::putDnsRecord);
    }

    public void putDnsRecord(DnsRecord record) {
        String uri = record.getUri().toString();
        var publicNginxRecord = record.getPublicNginxRecord();
        var localNginxRecord = record.getLocalNginxRecord();

        if (publicNginxRecord != null) {
            this.putNginxRecord(uri, publicNginxRecord, publicNginxService);
            this.putCloudflareRecord(publicNginxRecord, this.viescloudCloudflareService, VIESCLOUD_DOMAIN);
        }

        if (localNginxRecord != null) {
            this.putNginxRecord(uri, localNginxRecord, localNginxService);
            this.putCloudflareRecord(localNginxRecord, this.vieslocalCloudflareService, VIESLOCAL_DOMAIN);
        }
    }

    private void putNginxRecord(String uri, NginxProxyHostResponse record, NginxService service) {
        if (service.getProxyHostByUri(uri) == null)
            service.createProxyHost(record);
        else
            service.putProxyHost(record);
    }

    private void putCloudflareRecord(NginxProxyHostResponse record, CloudflareService cloudflareService, String dns) {
        if(ObjectUtils.isEmpty(record.getDomainNames())) {
            return;
        }

        record.getDomainNames().forEach(domainName -> {
            if (cloudflareService.getCloudflareCnameRecordByName(domainName) == null) {
                var dateTime = DateTime.now().getDateTime();
                var request = CloudflareRequest.builder()
                        .name(domainName)
                        .content(dns)
                        .proxied(true)
                        .ttl(1)
                        .type("CNAME")
                        .tags(List.of(record.getForwardHost(), dateTime))
                        .comment(String.format("Auto-added by DNS Manager on: %s", dateTime))
                        .build();
    
                cloudflareService.postCloudflareRecord(request);
            }
        });
    }

    public void deleteDnsRecord(String uri) {
        var record = this.getDnsRecordMap().get(uri);
        if (record != null) {
            this.publicNginxService.deleteProxyHost(record.getPublicNginxRecord().getId());
            this.localNginxService.deleteProxyHost(record.getLocalNginxRecord().getId());
        }
    }

    public void cleanUnusedCloudflareCnameDns() { 
        cleanUnusedCloudflareCnameDns(this.viescloudCloudflareService, this.publicNginxService);
        cleanUnusedCloudflareCnameDns(this.vieslocalCloudflareService, this.localNginxService);
    }

    private void cleanUnusedCloudflareCnameDns(CloudflareService cloudflareService, NginxService publicNginxService) {
        var cloudflareDnsResult = cloudflareService.getAllCloudflareCnameRecord();
        var domainNames = publicNginxService.getAllDomainNameList();
        
        for (String domainName : domainNames) {
            cloudflareDnsResult.removeIf(cloudflareDns -> cloudflareDns.getName().equals(domainName));
        }
        
        cloudflareDnsResult.forEach(e -> {
            cloudflareService.deleteCloudflareRecord(e.getId());
        });
    }


}
