package com.integralblue.availability.service.impl;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import javax.annotation.PostConstruct;

import lombok.NonNull;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import microsoft.exchange.webservices.data.core.ExchangeService;
import microsoft.exchange.webservices.data.core.enumeration.availability.AvailabilityData;
import microsoft.exchange.webservices.data.core.enumeration.misc.error.ServiceError;
import microsoft.exchange.webservices.data.core.enumeration.property.LegacyFreeBusyStatus;
import microsoft.exchange.webservices.data.core.exception.service.remote.ServiceResponseException;
import microsoft.exchange.webservices.data.core.response.AttendeeAvailability;
import microsoft.exchange.webservices.data.credential.WebCredentials;
import microsoft.exchange.webservices.data.misc.availability.AttendeeInfo;
import microsoft.exchange.webservices.data.misc.availability.AvailabilityOptions;
import microsoft.exchange.webservices.data.misc.availability.GetUserAvailabilityResults;
import microsoft.exchange.webservices.data.misc.availability.TimeWindow;
import microsoft.exchange.webservices.data.property.complex.EmailAddress;
import microsoft.exchange.webservices.data.property.complex.availability.Suggestion;
import microsoft.exchange.webservices.data.property.complex.availability.TimeSuggestion;

import org.apache.commons.lang3.time.DateUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.annotation.CacheConfig;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.util.Assert;

import com.google.common.collect.Lists;
import com.integralblue.availability.NotFoundException;
import com.integralblue.availability.model.Availability;
import com.integralblue.availability.model.CalendarEvent;
import com.integralblue.availability.model.FreeBusyStatus;
import com.integralblue.availability.model.Room;
import com.integralblue.availability.model.RoomList;
import com.integralblue.availability.properties.ExchangeConnectionProperties;
import com.integralblue.availability.service.AvailabilityService;

@Service
@CacheConfig(cacheNames= {"exchange"})
@Slf4j
public class ExchangeAvailabilityService implements AvailabilityService {
	@Autowired
	private ExchangeConnectionProperties exchangeConnectionProperties;
	
	private ExchangeService exchangeService;


	@Override
	@SneakyThrows
	public Map<String, Optional<Availability>> getAvailability(List<String> emailAddresses, Date start,
			Date end) {
		Assert.notEmpty(emailAddresses, "emailAddresses cannot be empty");
		Assert.isTrue(! start.after(end), "start must not be after end");
		final Map<String, Optional<Availability>> ret = new HashMap<>();

		final AvailabilityOptions availabilityOptions = new AvailabilityOptions();
		availabilityOptions.setMeetingDuration(30);

		// minimum time frame allowed by API is 24 hours
		final GetUserAvailabilityResults results = exchangeService.getUserAvailability(emailAddresses.stream().map(AttendeeInfo::new).collect(Collectors.toList()),
				new TimeWindow(start, end.before(DateUtils.addDays(start, 1))?DateUtils.addDays(start, 1):end), AvailabilityData.FreeBusyAndSuggestions,availabilityOptions);

		Assert.isTrue(results.getAttendeesAvailability().getCount() == emailAddresses.size());
		for(int attendeesAvailabilityIndex=0;attendeesAvailabilityIndex<results.getAttendeesAvailability().getCount();attendeesAvailabilityIndex++){
			AttendeeAvailability attendeeAvailability;
			try {
				attendeeAvailability = results.getAttendeesAvailability().getResponseAtIndex(attendeesAvailabilityIndex);
				attendeeAvailability.throwIfNecessary();
			} catch (ServiceResponseException e) {
				if (e.getErrorCode() == ServiceError.ErrorMailRecipientNotFound) {
					ret.put(emailAddresses.get(attendeesAvailabilityIndex), Optional.empty());
					continue;
				} else {
					throw e;
				}
			}
	
			FreeBusyStatus statusAtStart = FreeBusyStatus.FREE;
			final List<CalendarEvent> calendarEvents = new ArrayList<>();
			for (final microsoft.exchange.webservices.data.property.complex.availability.CalendarEvent calendarEvent : attendeeAvailability.getCalendarEvents()) {
				if(start.compareTo(calendarEvent.getEndTime()) < 0 && calendarEvent.getStartTime().compareTo(start) <= 0){
					switch (calendarEvent.getFreeBusyStatus()) {
					case Busy:
						statusAtStart = FreeBusyStatus.BUSY;
						break;
					case Free:
						// do nothing
						break;
					case NoData:
						// do nothing
						break;
					case OOF:
						// do nothing
						break;
					case Tentative:
						if(statusAtStart == FreeBusyStatus.FREE){
							statusAtStart = FreeBusyStatus.TENTATIVE;
						}
						break;
					}
				}
				
				if(start.compareTo(calendarEvent.getEndTime()) < 0 && calendarEvent.getStartTime().compareTo(end) < 0){
					calendarEvents.add(
							CalendarEvent.builder()
							.start(calendarEvent.getStartTime())
							.end(calendarEvent.getEndTime())
							.status(legacyFreeBusyStatusToFreeBusyStatus(calendarEvent.getFreeBusyStatus()))
							.location(calendarEvent.getDetails()==null?null:calendarEvent.getDetails().getLocation())
							.subject(calendarEvent.getDetails()==null?null:calendarEvent.getDetails().getSubject())
							.id(calendarEvent.getDetails()==null?null:calendarEvent.getDetails().getStoreId())
						.build());
				}
			}
			
			Date nextFree = null;
			for(final Suggestion suggestion : results.getSuggestions()){
				for(final TimeSuggestion timeSuggestion : suggestion.getTimeSuggestions()){
					if(nextFree==null || nextFree.after(timeSuggestion.getMeetingTime())){
						nextFree = timeSuggestion.getMeetingTime();
					}
				}
			}

			ret.put(emailAddresses.get(attendeesAvailabilityIndex), Optional.of(Availability.builder().statusAtStart(statusAtStart).nextFree(nextFree).calendarEvents(Collections.unmodifiableList(calendarEvents)).build()));
		}
		return Collections.unmodifiableMap(ret);
	}
	
	@Override
	public Optional<Availability> getAvailability(@NonNull String emailAddress, @NonNull Date start, @NonNull Date end) {
		Map<String, Optional<Availability>> ret = getAvailability(Collections.singletonList(emailAddress), start, end);
		Assert.isTrue(ret.keySet().size()==1);
		Assert.notNull(ret.get(emailAddress));
		return ret.get(emailAddress);
	}

	@SneakyThrows
	@PostConstruct
	private void postConstruct() {
		final ExchangeService exchangeService = new ExchangeService();
		exchangeService.setCredentials(new WebCredentials(exchangeConnectionProperties.getCredentials().getUsername(), exchangeConnectionProperties.getCredentials().getPassword(),exchangeConnectionProperties.getCredentials().getDomain()));
		exchangeService.setUrl(exchangeConnectionProperties.getUri());
		this.exchangeService = exchangeService;
	}

	@SneakyThrows
	@Override
	@Cacheable
	public Set<RoomList> getRoomLists() {
		final Set<RoomList> roomLists = new HashSet<>();
		final Set<String> addressesFromExchange = new HashSet<>();
		for(EmailAddress emailAddress : exchangeService.getRoomLists()){
			roomLists.add(RoomList.builder().emailAddress(emailAddress.getAddress()).name(emailAddress.getName()).build());
			addressesFromExchange.add(emailAddress.getAddress());
		}
		for(String emailAddress : exchangeConnectionProperties.getRoomLists().keySet()){
			if(!addressesFromExchange.contains(emailAddress))
				roomLists.add(RoomList.builder().emailAddress(emailAddress).name(emailAddress).build());
		}
		return Collections.unmodifiableSet(roomLists);
	}

	@SneakyThrows
	@Override
	@Cacheable
	public Optional<Set<Room>> getRooms(@NonNull String roomListEmailAddress) {
		if(exchangeConnectionProperties.getRoomLists().containsKey(roomListEmailAddress)){
			return Optional.of(
					Collections.unmodifiableSet(
							exchangeConnectionProperties.getRoomLists().get(roomListEmailAddress).stream().map(emailAddress ->
								Room.builder().emailAddress(emailAddress).name(emailAddress).build())
							.collect(Collectors.toSet())));
		}else{
			final Set<Room> roomLists = new HashSet<>();
			Collection<EmailAddress> rooms;
			try{
				rooms=exchangeService.getRooms(new EmailAddress(roomListEmailAddress));
			} catch (ServiceResponseException e) {
				if (e.getErrorCode() == ServiceError.ErrorNameResolutionNoResults) {
					return Optional.empty();
				} else {
					throw e;
				}
			}
			for(EmailAddress emailAddress : rooms){
				roomLists.add(Room.builder().emailAddress(emailAddress.getAddress()).name(emailAddress.getName()).build());
			}
			return Optional.of(Collections.unmodifiableSet(roomLists));
		}
	}
	
	private static FreeBusyStatus legacyFreeBusyStatusToFreeBusyStatus(@NonNull LegacyFreeBusyStatus legacyFreeBusyStatus){
		switch(legacyFreeBusyStatus){
		case Busy:
			return FreeBusyStatus.BUSY;
		case Free:
			return FreeBusyStatus.FREE;
		case Tentative:
			return FreeBusyStatus.TENTATIVE;
		default:
			return FreeBusyStatus.FREE;
		}
	}

	@Override
	@Cacheable
	public Map<Room, FreeBusyStatus> getCurrentRoomsStatus(String roomListEmailAddress) {
		Optional<Set<Room>> rooms = this.getRooms(roomListEmailAddress);
		Map<String, Room> emailAddressToRoom = getRooms(roomListEmailAddress).orElseThrow(NotFoundException::new).stream().collect(Collectors.toMap(Room::getEmailAddress, Function.identity()));
		Map<String, Optional<Availability>> emailAddressToOptionalAvailability = getAvailability(rooms.orElseThrow(NotFoundException::new).stream().map(room -> room.getEmailAddress()).collect(Collectors.toList()), new Date(), new Date());
		return emailAddressToOptionalAvailability.entrySet().stream().collect(Collectors.toMap(entry -> emailAddressToRoom.get(entry.getKey()), entry -> entry.getValue().map(Availability::getStatusAtStart).get()));
	}
}
