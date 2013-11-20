// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.
//
// Automatically generated by addcopyright.py at 01/29/2013
package com.cloud.baremetal.networkservice;

import javax.ejb.Local;
import javax.inject.Inject;

import org.apache.log4j.Logger;
import org.apache.cloudstack.api.ApiConstants;
import org.apache.cloudstack.engine.orchestration.service.NetworkOrchestrationService;

import com.cloud.dc.DataCenter;
import com.cloud.dc.Pod;
import com.cloud.dc.PodVlanMapVO;
import com.cloud.dc.Vlan;
import com.cloud.dc.Vlan.VlanType;
import com.cloud.dc.dao.DataCenterDao;
import com.cloud.dc.dao.PodVlanMapDao;
import com.cloud.dc.dao.VlanDao;
import com.cloud.deploy.DeployDestination;
import com.cloud.exception.ConcurrentOperationException;
import com.cloud.exception.InsufficientAddressCapacityException;
import com.cloud.exception.InsufficientVirtualNetworkCapcityException;
import com.cloud.host.HostVO;
import com.cloud.host.dao.HostDao;
import com.cloud.hypervisor.Hypervisor.HypervisorType;
import com.cloud.network.IpAddressManager;
import com.cloud.network.Network;
import com.cloud.network.Networks.AddressFormat;
import com.cloud.network.Networks.BroadcastDomainType;
import com.cloud.network.Networks.IsolationType;
import com.cloud.network.addr.PublicIp;
import com.cloud.network.dao.IPAddressDao;
import com.cloud.network.dao.IPAddressVO;
import com.cloud.network.guru.DirectPodBasedNetworkGuru;
import com.cloud.network.guru.NetworkGuru;
import com.cloud.offerings.dao.NetworkOfferingDao;
import com.cloud.utils.db.Transaction;
import com.cloud.utils.db.TransactionCallbackNoReturn;
import com.cloud.utils.db.TransactionStatus;
import com.cloud.vm.NicProfile;
import com.cloud.vm.ReservationContext;
import com.cloud.vm.VirtualMachineProfile;

@Local(value = {NetworkGuru.class})
public class BaremetaNetworkGuru extends DirectPodBasedNetworkGuru {
    private static final Logger s_logger = Logger.getLogger(BaremetaNetworkGuru.class);
    @Inject
    private HostDao _hostDao;
    @Inject
    DataCenterDao _dcDao;
    @Inject
    VlanDao _vlanDao;
    @Inject
    NetworkOrchestrationService _networkMgr;
    @Inject
    IPAddressDao _ipAddressDao;
    @Inject
    NetworkOfferingDao _networkOfferingDao;
    @Inject
    PodVlanMapDao _podVlanDao;
    @Inject
    IpAddressManager _ipAddrMgr;

    @Override
    public void reserve(NicProfile nic, Network network, VirtualMachineProfile vm, DeployDestination dest, ReservationContext context)
        throws InsufficientVirtualNetworkCapcityException, InsufficientAddressCapacityException, ConcurrentOperationException {
        if (dest.getHost().getHypervisorType() != HypervisorType.BareMetal) {
            super.reserve(nic, network, vm, dest, context);
            return;
        }

        HostVO host = _hostDao.findById(dest.getHost().getId());
        _hostDao.loadDetails(host);
        String intentIp = host.getDetail(ApiConstants.IP_ADDRESS);
        if (intentIp == null) {
            super.reserve(nic, network, vm, dest, context);
            return;
        }

        String oldIp = nic.getIp4Address();
        boolean getNewIp = false;
        if (oldIp == null) {
            getNewIp = true;
        } else {
            // we need to get a new ip address if we try to deploy a vm in a
            // different pod
            final IPAddressVO ipVO = _ipAddressDao.findByIpAndSourceNetworkId(network.getId(), oldIp);
            if (ipVO != null) {
                PodVlanMapVO mapVO = _podVlanDao.listPodVlanMapsByVlan(ipVO.getVlanId());
                if (mapVO.getPodId() != dest.getPod().getId()) {
                    Transaction.execute(new TransactionCallbackNoReturn() {
                        @Override
                        public void doInTransactionWithoutResult(TransactionStatus status) {
                            // release the old ip here
                            _ipAddrMgr.markIpAsUnavailable(ipVO.getId());
                            _ipAddressDao.unassignIpAddress(ipVO.getId());
                        }
                    });

                    nic.setIp4Address(null);
                    getNewIp = true;
                }
            }
        }

        if (getNewIp) {
            // we don't set reservationStrategy to Create because we need this
            // method to be called again for the case when vm fails to deploy in
            // Pod1, and we try to redeploy it in Pod2
            getBaremetalIp(nic, dest.getPod(), vm, network, intentIp);
        }

        DataCenter dc = _dcDao.findById(network.getDataCenterId());
        nic.setDns1(dc.getDns1());
        nic.setDns2(dc.getDns2());

        /*
         * Pod pod = dest.getPod(); Pair<String, Long> ip =
         * _dcDao.allocatePrivateIpAddress(dest.getDataCenter().getId(),
         * dest.getPod().getId(), nic.getId(), context.getReservationId(),
         * intentIp); if (ip == null) { throw new
         * InsufficientAddressCapacityException
         * ("Unable to get a management ip address", Pod.class, pod.getId()); }
         *
         * nic.setIp4Address(ip.first());
         * nic.setMacAddress(NetUtils.long2Mac(NetUtils
         * .createSequenceBasedMacAddress(ip.second())));
         * nic.setGateway(pod.getGateway()); nic.setFormat(AddressFormat.Ip4);
         * String netmask = NetUtils.getCidrNetmask(pod.getCidrSize());
         * nic.setNetmask(netmask);
         * nic.setBroadcastType(BroadcastDomainType.Native);
         * nic.setBroadcastUri(null); nic.setIsolationUri(null);
         */

        s_logger.debug("Allocated a nic " + nic + " for " + vm);
    }

    private void getBaremetalIp(NicProfile nic, Pod pod, VirtualMachineProfile vm, Network network, String requiredIp) throws InsufficientVirtualNetworkCapcityException,
        InsufficientAddressCapacityException, ConcurrentOperationException {
        DataCenter dc = _dcDao.findById(pod.getDataCenterId());
        if (nic.getIp4Address() == null) {
            s_logger.debug(String.format("Requiring ip address: %s", nic.getIp4Address()));
            PublicIp ip = _ipAddrMgr.assignPublicIpAddress(dc.getId(), pod.getId(), vm.getOwner(), VlanType.DirectAttached, network.getId(), requiredIp, false);
            nic.setIp4Address(ip.getAddress().toString());
            nic.setFormat(AddressFormat.Ip4);
            nic.setGateway(ip.getGateway());
            nic.setNetmask(ip.getNetmask());
            if (ip.getVlanTag() != null && ip.getVlanTag().equalsIgnoreCase(Vlan.UNTAGGED)) {
                nic.setIsolationUri(IsolationType.Ec2.toUri(Vlan.UNTAGGED));
                nic.setBroadcastUri(BroadcastDomainType.Vlan.toUri(Vlan.UNTAGGED));
                nic.setBroadcastType(BroadcastDomainType.Native);
            }
            nic.setReservationId(String.valueOf(ip.getVlanTag()));
            nic.setMacAddress(ip.getMacAddress());
        }
        nic.setDns1(dc.getDns1());
        nic.setDns2(dc.getDns2());
    }
}
