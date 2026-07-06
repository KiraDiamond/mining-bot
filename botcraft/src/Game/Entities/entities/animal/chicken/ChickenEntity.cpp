#include "botcraft/Game/Entities/entities/animal/chicken/ChickenEntity.hpp"

#if PROTOCOL_VERSION > 769 /* > 1.21.4 */
#include <mutex>
#endif

namespace Botcraft
{
#if PROTOCOL_VERSION > 769 /* > 1.21.4 */
    const std::array<std::string, ChickenEntity::metadata_count> ChickenEntity::metadata_names{ {
        "data_variant_id",
#if PROTOCOL_VERSION > 774 /* > 1.21.11 */
        "data_sound_variant_id",
#endif
    }};
#endif

    ChickenEntity::ChickenEntity()
    {
#if PROTOCOL_VERSION > 769 /* > 1.21.4 */
        SetDataVariantId(0);
#endif
#if PROTOCOL_VERSION > 774 /* > 1.21.11 */
        SetDataSoundVariantId(0);
#endif
        // Initialize all attributes with default values
        attributes.insert({ EntityAttribute::Type::MaxHealth, EntityAttribute(EntityAttribute::Type::MaxHealth, 4.0) });
        attributes.insert({ EntityAttribute::Type::MovementSpeed, EntityAttribute(EntityAttribute::Type::MovementSpeed, 0.25) });
    }

    ChickenEntity::~ChickenEntity()
    {

    }


    std::string ChickenEntity::GetName() const
    {
        return "chicken";
    }

    EntityType ChickenEntity::GetType() const
    {
        return EntityType::Chicken;
    }


    std::string ChickenEntity::GetClassName()
    {
        return "chicken";
    }

    EntityType ChickenEntity::GetClassType()
    {
        return EntityType::Chicken;
    }

#if PROTOCOL_VERSION > 769 /* > 1.21.4 */
    ProtocolCraft::Json::Value ChickenEntity::Serialize() const
    {
        ProtocolCraft::Json::Value output = AnimalEntity::Serialize();
        output["metadata"]["data_variant_id"] = GetDataVariantId();
#if PROTOCOL_VERSION > 774 /* > 1.21.11 */
        output["metadata"]["data_sound_variant_id"] = GetDataSoundVariantId();
#endif

        return output;
    }

    void ChickenEntity::SetMetadataValue(const int index, const std::any& value)
    {
        if (index < hierarchy_metadata_count)
        {
            AnimalEntity::SetMetadataValue(index, value);
        }
        else if (index - hierarchy_metadata_count < metadata_count)
        {
            std::scoped_lock<std::shared_mutex> lock(entity_mutex);
            metadata[metadata_names[index - hierarchy_metadata_count]] = value;
        }
    }

    int ChickenEntity::GetDataVariantId() const
    {
        std::shared_lock<std::shared_mutex> lock(entity_mutex);
        return std::any_cast<int>(metadata.at("data_variant_id"));
    }

#if PROTOCOL_VERSION > 774 /* > 1.21.11 */
    int ChickenEntity::GetDataSoundVariantId() const
    {
        std::shared_lock<std::shared_mutex> lock(entity_mutex);
        return std::any_cast<int>(metadata.at("data_sound_variant_id"));
    }
#endif


    void ChickenEntity::SetDataVariantId(const int data_variant_id)
    {
        std::scoped_lock<std::shared_mutex> lock(entity_mutex);
        metadata["data_variant_id"] = data_variant_id;
    }

#if PROTOCOL_VERSION > 774 /* > 1.21.11 */
    void ChickenEntity::SetDataSoundVariantId(const int data_sound_variant_id)
    {
        std::scoped_lock<std::shared_mutex> lock(entity_mutex);
        metadata["data_sound_variant_id"] = data_sound_variant_id;
    }
#endif
#endif

    double ChickenEntity::GetWidthImpl() const
    {
        return 0.4;
    }

    double ChickenEntity::GetHeightImpl() const
    {
        return 0.7;
    }

}
